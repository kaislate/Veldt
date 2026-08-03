// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.db.toEntity
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.library.tag.TagReader
import com.kaislate.veldtplayer.data.library.tag.TrackTags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scan pipeline end to end, driving the **production** [LibraryScanWorker.doWork] over a real
 * Room database.
 *
 * Why this file exists at all: `ScanDifferTest` proves the diff and `SongDaoTest` proves the two
 * queries, but every claim about how the worker *wires them together* — that it passes its own
 * `LibrarySource.id` to both the read and the delete, that it filters `scanned` by the same key the
 * diff emitted, that an unchanged library causes no writes — lived only in prose, and each of those
 * is exactly the "logic locally correct, call site collapses two inputs" shape this project keeps
 * finding. A fake DAO asserting its own model would not have helped; this uses the real one.
 *
 * The fixtures keep `Song.id` and `Song.externalId` unequal (Global Constraint 14), so any code that
 * silently swapped one for the other picks the wrong row rather than agreeing by coincidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScanWorkerTest {

    private companion object {
        const val THIS_SOURCE = "test-source"
        const val OTHER_SOURCE = "other-source"
    }

    /** Records the two writes the worker can make, with their arguments, and delegates the rest. */
    private class RecordingSongDao(private val delegate: SongDao) : SongDao {
        val upsertedExternalIds = mutableListOf<String>()
        val deletes = mutableListOf<Pair<String, List<String>>>()

        // Recorded, then delegated to the REAL dao — the id-preserving transaction is production
        // behaviour and must actually run, not be modelled here.
        override suspend fun upsertBySourceKey(rows: List<SongEntity>) {
            upsertedExternalIds += rows.map { it.externalId }
            delegate.upsertBySourceKey(rows)
        }

        override suspend fun findIdBySourceKey(sourceId: String, externalId: String) =
            delegate.findIdBySourceKey(sourceId, externalId)

        override suspend fun insertReplacing(row: SongEntity) = delegate.insertReplacing(row)

        override suspend fun deleteByExternalIds(sourceId: String, externalIds: List<String>) {
            deletes += sourceId to externalIds
            delegate.deleteByExternalIds(sourceId, externalIds)
        }

        override fun observeAllSongs(): Flow<List<SongEntity>> = delegate.observeAllSongs()
        override suspend fun getAllSongs() = delegate.getAllSongs()
        override suspend fun getSongsByAlbum(album: String) = delegate.getSongsByAlbum(album)
        override suspend fun getSongsByArtist(artist: String) = delegate.getSongsByArtist(artist)
        override suspend fun search(pattern: String) = delegate.search(pattern)
        override fun observeSearch(pattern: String) = delegate.observeSearch(pattern)
        override suspend fun getIndex(sourceId: String) = delegate.getIndex(sourceId)
        override suspend fun clear() = delegate.clear()
    }

    /** Enumerates whatever the test set; everything else is out of scope for the scan. */
    private class FakeSource(
        override val id: String,
        var songs: List<Song> = emptyList(),
    ) : LibrarySource {
        override suspend fun listSongs(): List<Song> = songs
        override suspend fun listAlbums(): List<Album> = emptyList()
        override suspend fun listArtists(): List<Artist> = emptyList()
        override suspend fun search(query: String): List<Song> = emptyList()
        override fun resolvePlayableUri(song: Song): String = song.uri
        override fun stableKey(song: Song): String = song.relativeKey ?: song.uri
    }

    /** The tag merge is `TagMergeTest`'s subject; here it must simply not alter the identity. */
    private object PassThroughTagReader : TagReader {
        override fun read(filePath: String?, fallback: TrackTags): TrackTags = fallback
    }

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var dao: RecordingSongDao
    private lateinit var source: FakeSource

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = RecordingSongDao(db.songDao())
        source = FakeSource(id = THIS_SOURCE)
    }

    @After fun tearDown() = db.close()

    private fun song(
        id: Long,
        externalId: String = "ms-${id + 9000}",
        mtime: Long = 100L,
        relativeKey: String? = "external_primary:Music/$id.mp3",
        title: String = "T$id",
    ) = Song(
        id = id,
        sourceId = THIS_SOURCE,
        externalId = externalId,
        uri = "content://media/external/audio/media/$id",
        filePath = "/storage/emulated/0/Music/$id.mp3",
        relativeKey = relativeKey,
        title = title,
        artist = "A",
        album = "Al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 1000L,
        dateModifiedSec = mtime,
        hasEmbeddedArt = false,
    )

    /** A row this scan does not own. Built directly so its `sourceId` cannot come from [song]. */
    private fun foreignRow(id: Long, externalId: String) = SongEntity(
        id = id, sourceId = OTHER_SOURCE, externalId = externalId,
        uri = "remote://track/$externalId", filePath = null, relativeKey = null,
        title = "foreign $externalId", artist = "A", album = "Al", albumArtist = null,
        trackNumber = null, discNumber = null, year = null, durationMs = 1000L,
        dateModifiedSec = 100L, hasEmbeddedArt = false,
    )

    private suspend fun seed(vararg rows: SongEntity) =
        db.songDao().upsertBySourceKey(rows.toList())

    private suspend fun runScan(): ListenableWorker.Result =
        TestListenableWorkerBuilder.from(context, LibraryScanWorker::class.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker =
                    LibraryScanWorker(appContext, workerParameters, source, PassThroughTagReader, dao)
            })
            .build()
            .doWork()

    private suspend fun storedIdentities(): List<Pair<String, String>> =
        db.songDao().getAllSongs().map { it.sourceId to it.externalId }.sortedBy { it.second }

    /**
     * **The anti-churn property on the production path.** `ScanDifferTest` proves the diff is empty
     * for an unchanged library; this proves the worker then writes nothing, which is the part a user
     * feels — an upsert here means re-reading every file's tags on every MediaStore notification.
     *
     * A foreign-source row is present throughout. It must be invisible to this scan: with an
     * unscoped `getIndex`, `rem-1` would arrive in `current`, fail to appear in the local scan, and
     * be classified `removed` — so the "no writes" claim and "no cross-source deletes" claim are the
     * same assertion here, and both are stated rather than one standing in for the other.
     */
    @Test fun `an unchanged rescan writes nothing, and never sees another source's rows`() = runTest {
        val a = song(1)
        val b = song(2)
        seed(a.toEntity(), b.toEntity(), foreignRow(id = 3, externalId = "rem-1"))
        source.songs = listOf(a, b)

        assertEquals(ListenableWorker.Result.success(), runScan())

        assertEquals("an unchanged library must not be re-upserted", emptyList<String>(), dao.upsertedExternalIds)
        assertEquals("an unchanged library must not delete anything", emptyList<Pair<String, List<String>>>(), dao.deletes)
        assertEquals(
            listOf(THIS_SOURCE to "ms-9001", THIS_SOURCE to "ms-9002", OTHER_SOURCE to "rem-1"),
            storedIdentities().sortedBy { it.second },
        )
    }

    /**
     * Source scoping through the worker, as the non-collapse of a named pair. The DB holds a local
     * `ms-9002` that has vanished from the scan and a **foreign** `ms-9002` that has not — the same
     * source-native id under two sources, which is legitimate and is why the unique index is a pair.
     *
     * Two things are asserted because two different mistakes are possible and each hides the other:
     *
     * - the delete is `(THIS_SOURCE, ["ms-9002"])` **exactly** — an unscoped `getIndex` would also
     *   put the foreign `rem-1` in `removed`, and the surviving-rows assertion alone would not
     *   notice, because a scoped DELETE would refuse to act on it anyway;
     * - the surviving rows are named — an unscoped DELETE would take the foreign `ms-9002` with the
     *   local one, and the delete-argument assertion alone would not notice, because the argument
     *   list is identical either way.
     */
    @Test fun `a scan deletes only its own source's vanished rows`() = runTest {
        val a = song(1)
        val b = song(2)
        seed(
            a.toEntity(), b.toEntity(),
            foreignRow(id = 3, externalId = "ms-9002"),
            foreignRow(id = 4, externalId = "rem-1"),
        )
        source.songs = listOf(a) // b has vanished from the local library

        assertEquals(ListenableWorker.Result.success(), runScan())

        assertEquals(listOf(THIS_SOURCE to listOf("ms-9002")), dao.deletes)
        assertEquals(
            listOf(THIS_SOURCE to "ms-9001", OTHER_SOURCE to "ms-9002", OTHER_SOURCE to "rem-1"),
            storedIdentities(),
        )
    }

    /**
     * The `touched` filter, which is where the diff's output meets the scan's rows. It must select
     * by the same key the diff emitted: a moved row and a new row are re-read and re-upserted, and
     * an untouched row is not.
     *
     * All three are asserted together, as one list naming WHICH externalIds were written. Asserting
     * only "the moved one was upserted" would pass for a worker that upserted everything — the whole
     *-library tag re-read the diff exists to prevent — and asserting only a count would pass for a
     * worker that upserted the wrong two of the three.
     */
    @Test fun `only the added and changed rows are upserted`() = runTest {
        val moved = song(1, relativeKey = "external_primary:Music/a.mp3")
        val untouched = song(2)
        seed(moved.toEntity(), untouched.toEntity())

        val movedNow = moved.copy(relativeKey = "external_primary:Podcasts/a.mp3") // same mtime
        val fresh = song(3)
        source.songs = listOf(movedNow, untouched, fresh)

        assertEquals(ListenableWorker.Result.success(), runScan())

        assertEquals(listOf("ms-9001", "ms-9003"), dao.upsertedExternalIds)
        assertEquals(emptyList<Pair<String, List<String>>>(), dao.deletes)
        // And the move actually landed, rather than the row merely being counted as written.
        assertEquals(
            "external_primary:Podcasts/a.mp3",
            db.songDao().getAllSongs().single { it.externalId == "ms-9001" }.relativeKey,
        )
    }
}
