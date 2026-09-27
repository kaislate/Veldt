// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toEntity
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.library.tag.TagReader
import com.kaislate.veldtplayer.data.library.tag.TrackTags
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.di.LocalLibrary
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * Enumerates the library via [LibrarySource], augments each touched row's tags via
 * [TagReader], then applies a [ScanDiffer] diff against the DB index — upserting
 * added+changed and deleting removed. A `@HiltWorker`; its non-`@Assisted` deps are
 * satisfied by the Hilt graph (see `di/LibraryModule` + `di/DatabaseModule`).
 *
 * Failure semantics (see plan review): only transient I/O ([IOException]) is retried,
 * and only within [MAX_ATTEMPTS] so a persistent I/O fault cannot loop forever under
 * WorkManager backoff. Any other throwable is a deterministic bug and returns
 * [Result.failure] immediately rather than retrying uselessly. A normally-ungranted
 * scan does NOT throw ([LibrarySource] returns empty), so it simply no-ops to success.
 *
 * This worker is **local-only by type**: it takes the [LocalLibrary]-qualified source rather than
 * one of the registry's, because a MediaStore enumeration is the only thing it knows how to do. A
 * remote source syncs through its own worker (design spec §5.4). That also keeps `ScanDiffer`'s
 * one-source precondition satisfied structurally — there is no set here to loop over and therefore
 * no way to hand the differ a concatenation of two sources' rows.
 *
 * **Tag-scan generation (API 29 legacy-storage fix).** [ScanDiffer] only re-reads tags for rows it
 * calls added or changed — deliberately, that is the whole anti-churn point — but that means a row
 * scanned while `_DATA` reads were failing (pre-`requestLegacyExternalStorage`, see
 * [com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader]'s KDoc) would keep its MediaStore-only
 * tags forever: it is neither added nor changed on the next scan, so it is never revisited. When
 * [SettingsRepository.tagScanGeneration] is below [CURRENT_TAG_SCAN_GENERATION], this scan treats
 * EVERY scanned local song as touched — not just the diff's added/changed — so every row gets one
 * real tag re-read, and on success the stored generation advances so this only happens once.
 */
@HiltWorker
class LibraryScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    // @LocalLibrary, not a source from the registry. This worker enumerates MediaStore and
    // nothing else — remote sync gets its own worker (design spec §5.4) — so the restriction is
    // part of the type Dagger resolves and needs no string to say it (Global Constraint 1).
    @LocalLibrary private val librarySource: LibrarySource,
    private val tagReader: TagReader,
    private val songDao: SongDao,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val scanned: List<Song> = librarySource.listSongs()
        // Every identity in this method is the source-native `externalId`, scoped to THIS source's
        // id — never `Song.id`, which is an app-internal handle a source cannot name (GC 5). The
        // same `librarySource.id` is passed to the read and to the delete, so a scan can neither
        // diff against nor destroy another source's rows.
        val current = songDao.getIndex(librarySource.id)
        val diff = ScanDiffer.diff(
            current = current,
            scanned = scanned.map { IndexEntry(it.externalId, it.dateModifiedSec, it.relativeKey) },
        )

        // See the class KDoc: a stored generation below current means this install may have rows
        // whose tags were read while `_DATA` was unreadable, and those rows are neither added nor
        // changed — so the touched set widens to every scanned row for exactly this one scan.
        val storedGeneration = settingsRepository.tagScanGeneration.first()
        val forceFullRetag = storedGeneration < CURRENT_TAG_SCAN_GENERATION
        val touched = if (forceFullRetag) {
            scanned.map { it.externalId }.toHashSet()
        } else {
            (diff.added + diff.changed).toHashSet()
        }
        val toUpsert = scanned.filter { it.externalId in touched }.map { song ->
            val fallback = TrackTags(
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumArtist = song.albumArtist,
                trackNumber = song.trackNumber,
                discNumber = song.discNumber,
                year = song.year,
                hasEmbeddedArt = song.hasEmbeddedArt,
            )
            // eAlvaTag parse, degrading to the MediaStore fallback on any failure.
            val tags = tagReader.read(song.filePath, fallback)
            song.copy(
                title = tags.title ?: song.title,
                artist = tags.artist ?: song.artist,
                album = tags.album ?: song.album,
                albumArtist = tags.albumArtist,
                trackNumber = tags.trackNumber,
                discNumber = tags.discNumber,
                year = tags.year,
                hasEmbeddedArt = tags.hasEmbeddedArt,
            ).toEntity()
        }

        // `upsertBySourceKey`, never a bare REPLACE: these rows carry `Song.UNSAVED` (the source
        // enumerated them and cannot know a surrogate), and a REPLACE would hand every changed row
        // a BRAND NEW id on every scan — silently invalidating every playlist entry cached against
        // the old one, here, on a background worker, with no user action to associate it with.
        if (toUpsert.isNotEmpty()) songDao.upsertBySourceKey(toUpsert)
        // Chunked (N2 Task 3): a local library can shed more rows in one scan (a whole album
        // directory deleted) than SQLite's 999-bound-variable limit tolerates in one `IN (...)`.
        if (diff.removed.isNotEmpty()) songDao.deleteAllByExternalIds(librarySource.id, diff.removed)
        // Only after the scan above actually succeeded, and only forward — advancing this is what
        // stops the forced full re-tag from repeating on every future scan.
        if (forceFullRetag) settingsRepository.setTagScanGeneration(CURRENT_TAG_SCAN_GENERATION)
        Result.success()
    } catch (io: IOException) {
        // Transient I/O (DB/storage) — retry, but only a bounded number of times.
        if (runAttemptCount + 1 < MAX_ATTEMPTS) {
            Log.w(TAG, "scan hit transient I/O; retrying (attempt ${runAttemptCount + 1})", io)
            Result.retry()
        } else {
            Log.e(TAG, "scan exhausted retries after transient I/O; failing", io)
            Result.failure()
        }
    } catch (t: Throwable) {
        // Deterministic bug (NPE, IllegalState, SQL constraint, ...) — retrying under
        // backoff would loop forever with the same input. Fail loudly instead.
        Log.e(TAG, "scan failed with a non-transient error; not retrying", t)
        Result.failure()
    }

    companion object {
        const val UNIQUE_NAME = "veldt-library-scan"
        private const val TAG = "LibraryScanWorker"
        private const val MAX_ATTEMPTS = 3

        /**
         * Bumped whenever a change to tag reading means an existing library needs every row
         * re-tagged, not just the rows [ScanDiffer] would call added/changed — see the class
         * KDoc. Currently 2: 1 was the pre-existing (undeclared) baseline, and 2 is this fix —
         * `android:requestLegacyExternalStorage` making `_DATA` paths readable again on API 29,
         * so a library scanned before the flag shipped gets a one-time full re-tag.
         */
        const val CURRENT_TAG_SCAN_GENERATION = 2

        /** Enqueue a unique one-time scan; keeps an in-flight scan rather than piling up. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LibraryScanWorker>().build(),
            )
        }
    }
}
