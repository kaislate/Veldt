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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
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
 */
@HiltWorker
class LibraryScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val librarySource: LibrarySource,
    private val tagReader: TagReader,
    private val songDao: SongDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val scanned: List<Song> = librarySource.listSongs()
        val current = songDao.getIndex()
        val diff = ScanDiffer.diff(
            current = current,
            scanned = scanned.map { IndexEntry(it.id, it.dateModifiedSec) },
        )

        val touchedIds = (diff.added + diff.changed).toHashSet()
        val toUpsert = scanned.filter { it.id in touchedIds }.map { song ->
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

        if (toUpsert.isNotEmpty()) songDao.upsertAll(toUpsert)
        if (diff.removed.isNotEmpty()) songDao.deleteByIds(diff.removed)
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
