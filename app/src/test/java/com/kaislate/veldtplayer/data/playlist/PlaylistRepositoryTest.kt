// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.db.toEntity
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK so Room starts under targetSdk 36.
@Config(sdk = [34])
class PlaylistRepositoryTest {

    /**
     * A stand-in library source. It mirrors [com.kaislate.veldtplayer.data.library.LocalSource]'s
     * two key functions exactly — playable uri embeds the id, stable key is the path — because the
     * difference between them is the property under test. `LocalSourceKeysTest` guards the real
     * implementation; this fake only has to be faithful to its shape.
     *
     * `listSongs()` deliberately throws: [PlaylistRepository.resolve] must read the tag-merged
     * Room projection, so a regression back to a live source enumeration fails loudly here rather
     * than passing quietly against different data.
     *
     * A fixture may name its source literally; production code must read it from
     * [LibrarySource.id] (Global Constraint 1/2).
     *
     * **The default id is `"test-source"`, not `"local"`.** It used to be `"local"`, which meant a
     * production `sourceId = "local"` hardcode agreed with this fake by coincidence in every test
     * here except the one that explicitly passes `"not-local"`. The canonical fake id exists so a
     * hardcode disagrees.
     *
     * **It now deliberately AGREES with `song()`'s default `sourceId`, which is the opposite of
     * what this KDoc used to require.** Before N0 Task 4 the two had to differ, because `addSongs`
     * read the source from the ambient `LibrarySource` and a matching Song field would have let a
     * regression pass by coincidence. Task 4 made the Song's own `sourceId` the routing key on
     * purpose, so a song must now name a source the registry actually holds — a mismatched default
     * would simply throw, testing nothing. See `song()`'s comment for where that protection went.
     */
    private class FakeSource(override val id: String = "test-source") : LibrarySource {
        override fun resolvePlayableUri(song: Song): String = song.uri
        override fun stableKey(song: Song): String =
            song.relativeKey ?: song.filePath ?: song.uri
        override suspend fun listSongs(): List<Song> =
            error("resolve() must read the Room songs projection, not the live source")
        override suspend fun listAlbums(): List<Album> = emptyList()
        override suspend fun listArtists(): List<Artist> = emptyList()
        override suspend fun search(query: String): List<Song> = emptyList()
    }

    /**
     * A [PlaylistDao] that counts the two write-backs [PlaylistRepository.resolve] performs.
     *
     * `resolve` is a READ that writes, and its one UI consumer (`PlaylistViewModel`) re-enters it
     * from a `mapLatest` over a flow that includes `observeEntries` — so every write re-triggers the
     * very call that made it. Convergence is therefore not a nice-to-have, it is the difference
     * between a settled screen and an endless re-resolve. Counting the writes is the only way to
     * assert it: a test that merely called `resolve` twice and checked the output would look
     * identical whether the loop quiesced or spun forever.
     */
    private class CountingDao(private val delegate: PlaylistDao) : PlaylistDao by delegate {
        var idWrites = 0
            private set
        var keyWrites = 0
            private set

        override suspend fun updateResolvedSongIds(updates: Map<Long, Long?>) {
            idWrites += updates.size
            delegate.updateResolvedSongIds(updates)
        }

        override suspend fun updateResolvedSourceKeys(updates: Map<Long, String>) {
            keyWrites += updates.size
            delegate.updateResolvedSourceKeys(updates)
        }

        fun reset() { idWrites = 0; keyWrites = 0 }
    }

    private lateinit var db: VeldtDatabase
    private lateinit var dao: PlaylistDao
    private lateinit var songDao: SongDao
    private lateinit var source: FakeSource
    private lateinit var repo: PlaylistRepository
    private var clock = 1_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), VeldtDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.playlistDao()
        songDao = db.songDao()
        source = FakeSource()
        repo = PlaylistRepository(dao, songDao, SourceRegistry(setOf(source))) { ++clock }
    }

    @After fun tearDown() = db.close()

    /**
     * Defaults to a DATA path and no relative key, so the bulk of this class exercises rung 2 of
     * the key ladder. [songWithoutDataPath] covers rung 1, which is the case a provider that
     * withholds DATA produces.
     */
    private fun song(
        id: Long,
        path: String?,
        title: String = "T$id",
        relativeKey: String? = null,
        sourceId: String = "test-source",
    ) = Song(
        id = id,
        // *** THIS FIELD'S MEANING WAS INVERTED BY N0 TASK 4 — read before "fixing" it. ***
        //
        // It used to be a DECOY, pinned to "wrong-source", because `addSongs` was required to take
        // the entry's source from the single ambient LibrarySource and NEVER from the Song. Task 4
        // deliberately reversed that rule: there is no single source any more, one `addSongs` call
        // may carry songs from two of them, so `addSongs` now routes on `registry.require(
        // it.sourceId)` — i.e. on this very field, BY DESIGN. A decoy value here is no longer a
        // trap for a regression; it is just an unregistered source that throws.
        //
        // The protection the decoy used to give did not disappear, it MOVED: `addSongs stores each
        // song's own source identity, not any single source's` is what now catches a call site that
        // reads the source from anywhere but the song, and it does it by asserting two different
        // sources come back on two entries — something one ambient source cannot fake.
        sourceId = sourceId,
        externalId = "ms-${id + 9000}",
        uri = "content://media/external/audio/media/$id",
        filePath = path,
        relativeKey = relativeKey,
        title = title,
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 1000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    /** A row from a provider that withholds `DATA`: no file path, relative key only. */
    private fun songWithoutDataPath(
        id: Long,
        relativeKey: String,
        title: String = "T$id",
        sourceId: String = "test-source",
    ) = song(id = id, path = null, title = title, relativeKey = relativeKey, sourceId = sourceId)

    /**
     * Replace the whole `songs` projection — i.e. what a library scan does. Clearing first is the
     * point: the scanner deletes and reinserts rows keyed on MediaStore `_ID`, so the same file
     * can come back under a different id, and that is the case the ladder exists for.
     */
    private suspend fun rescanLibraryAs(vararg songs: Song) {
        songDao.clear()
        songDao.upsertBySourceKey(songs.map { it.toEntity() })
    }

    private suspend fun positions(playlistId: Long) =
        dao.getEntries(playlistId).map { it.position }

    private suspend fun keys(playlistId: Long) =
        dao.getEntries(playlistId).map { it.sourceKey }

    // ---- CRUD -------------------------------------------------------------------------------

    @Test fun `create rename and delete round-trip through the observable list`() = runTest {
        val id = repo.create("Gym")
        assertEquals(listOf("Gym"), repo.observe().first().map { it.name })

        repo.rename(id, "Gym v2")
        val renamed = repo.observe().first().single()
        assertEquals("Gym v2", renamed.name)
        assertTrue("rename must bump updatedAt", renamed.updatedAt > renamed.createdAt)

        repo.delete(id)
        assertEquals(emptyList<PlaylistEntity>(), repo.observe().first())
    }

    // ---- addSongs ---------------------------------------------------------------------------

    @Test fun `addSongs appends in the order given`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, listOf(song(1, "/a.mp3"), song(2, "/b.mp3")))
        repo.addSongs(pl, listOf(song(3, "/c.mp3")))
        assertEquals(listOf("/a.mp3", "/b.mp3", "/c.mp3"), keys(pl))
        assertEquals(listOf(0, 1, 2), positions(pl))
    }

    // A playlist may legitimately hold the same track twice; swallowing the second add would be
    // the wrong surprise. This pins the decision so nobody "fixes" it into a dedupe later.
    @Test fun `addSongs does not dedupe the same track added twice`() = runTest {
        val pl = repo.create("Mix")
        val s = song(1, "/a.mp3")
        repo.addSongs(pl, listOf(s))
        repo.addSongs(pl, listOf(s))
        assertEquals(listOf("/a.mp3", "/a.mp3"), keys(pl))
        assertEquals(listOf(0, 1), positions(pl))
    }

    /**
     * Global Constraint 2. The source is deliberately NOT named "local" here, so a production
     * `sourceId = "local"` literal fails this test instead of passing by coincidence.
     */
    @Test fun `addSongs takes source identity from the LibrarySource, never a literal`() = runTest {
        val other = FakeSource(id = "not-local")
        val otherRepo = PlaylistRepository(dao, songDao, SourceRegistry(setOf(other))) { ++clock }
        val pl = otherRepo.create("Mix")
        otherRepo.addSongs(pl, listOf(song(1, "/a.mp3", sourceId = "not-local")))
        val entry = dao.getEntries(pl).single()
        assertEquals("not-local", entry.sourceId)
        assertEquals("/a.mp3", entry.sourceKey)
    }

    /**
     * The stored key must be [LibrarySource.stableKey], NOT the playable uri. The uri embeds the
     * MediaStore `_ID`, so keying on it would make the entry unresolvable after precisely the
     * rescan that re-resolution exists to survive.
     */
    @Test fun `addSongs stores the stable key, not the playable uri`() = runTest {
        val pl = repo.create("Mix")
        val s = song(3, "/a.mp3")
        repo.addSongs(pl, listOf(s))
        val entry = dao.getEntries(pl).single()
        assertEquals(source.stableKey(s), entry.sourceKey)
        assertNotEquals(source.resolvePlayableUri(s), entry.sourceKey)
    }

    @Test fun `addSongs denormalises the display strings and caches the resolved id`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, listOf(song(9, "/a.mp3", title = "Alpha")))
        val entry = dao.getEntries(pl).single()
        assertEquals("Alpha", entry.sourceTitle)
        assertEquals("Artist", entry.sourceArtist)
        assertEquals("Album", entry.sourceAlbum)
        assertEquals(9L, entry.songId)
    }

    @Test fun `addSongs with an empty list changes nothing`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, emptyList())
        assertEquals(emptyList<PlaylistEntryEntity>(), dao.getEntries(pl))
    }

    // ---- the dense-position invariant -------------------------------------------------------

    /**
     * The invariant that has no schema backing: there is deliberately no unique index on
     * `(playlistId, position)`, because one would abort a shift-by-one reorder mid-transaction.
     * So every write path has to keep positions dense `0..n-1` on its own, and this asserts that
     * directly rather than inferring it from reorder's output.
     */
    @Test fun `positions stay dense 0 to n-1 across add, remove and move`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, (1..5).map { song(it.toLong(), "/$it.mp3") })
        assertEquals(listOf(0, 1, 2, 3, 4), positions(pl))

        // remove from the middle: the tail must close up, not leave a hole at 2
        repo.remove(dao.getEntries(pl)[2].id)
        assertEquals(listOf(0, 1, 2, 3), positions(pl))
        assertEquals(listOf("/1.mp3", "/2.mp3", "/4.mp3", "/5.mp3"), keys(pl))

        // remove the head: everything shifts down
        repo.remove(dao.getEntries(pl).first().id)
        assertEquals(listOf(0, 1, 2), positions(pl))

        // a move renumbers the whole sequence
        repo.move(pl, 2, 0)
        assertEquals(listOf(0, 1, 2), positions(pl))
        assertEquals(listOf("/5.mp3", "/2.mp3", "/4.mp3"), keys(pl))

        // and appending after all that lands at the end, still gap-free
        repo.addSongs(pl, listOf(song(6, "/6.mp3")))
        assertEquals(listOf(0, 1, 2, 3), positions(pl))
        assertEquals("/6.mp3", keys(pl).last())

        // remove the tail: no gap, no off-by-one
        repo.remove(dao.getEntries(pl).last().id)
        assertEquals(listOf(0, 1, 2), positions(pl))
    }

    @Test fun `removing the only entry empties the playlist`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, listOf(song(1, "/a.mp3")))
        repo.remove(dao.getEntries(pl).single().id)
        assertEquals(emptyList<PlaylistEntryEntity>(), dao.getEntries(pl))
        // and the next add restarts at 0 rather than inheriting a stale max
        repo.addSongs(pl, listOf(song(2, "/b.mp3")))
        assertEquals(listOf(0), positions(pl))
    }

    @Test fun `removing an unknown entry id is a no-op`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, listOf(song(1, "/a.mp3"), song(2, "/b.mp3")))
        repo.remove(99_999L)
        assertEquals(listOf(0, 1), positions(pl))
    }

    @Test fun `remove only touches the owning playlist`() = runTest {
        val gym = repo.create("Gym")
        val chill = repo.create("Chill")
        repo.addSongs(gym, listOf(song(1, "/g1.mp3"), song(2, "/g2.mp3")))
        repo.addSongs(chill, listOf(song(3, "/c1.mp3"), song(4, "/c2.mp3")))
        repo.remove(dao.getEntries(gym).first().id)
        assertEquals(listOf("/g2.mp3"), keys(gym))
        assertEquals(listOf("/c1.mp3", "/c2.mp3"), keys(chill))
        assertEquals(listOf(0, 1), positions(chill))
    }

    // ---- move -------------------------------------------------------------------------------

    @Test fun `move down slides the span between the indices up`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, (0..4).map { song(it.toLong(), "/$it.mp3") })
        repo.move(pl, 0, 3)
        assertEquals(listOf("/1.mp3", "/2.mp3", "/3.mp3", "/0.mp3", "/4.mp3"), keys(pl))
    }

    @Test fun `move up slides the span between the indices down`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, (0..4).map { song(it.toLong(), "/$it.mp3") })
        repo.move(pl, 3, 1)
        assertEquals(listOf("/0.mp3", "/3.mp3", "/1.mp3", "/2.mp3", "/4.mp3"), keys(pl))
    }

    // A drag must not invalidate the entry id the UI is holding for a later remove.
    @Test fun `move preserves entry row ids`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, (0..3).map { song(it.toLong(), "/$it.mp3") })
        val before = dao.getEntries(pl).associate { it.sourceKey to it.id }
        repo.move(pl, 0, 3)
        val after = dao.getEntries(pl).associate { it.sourceKey to it.id }
        assertEquals(before, after)
    }

    @Test fun `move with out-of-range or identical indices changes nothing`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, (0..2).map { song(it.toLong(), "/$it.mp3") })
        val before = keys(pl)
        repo.move(pl, 1, 1)
        repo.move(pl, -1, 0)
        repo.move(pl, 0, 7)
        repo.move(pl, 7, 0)
        assertEquals(before, keys(pl))
        assertEquals(listOf(0, 1, 2), positions(pl))
    }

    // ---- resolve: the re-resolution ladder ---------------------------------------------------

    @Test fun `resolve joins entries to the current library in position order`() = runTest {
        val pl = repo.create("Mix")
        val library = listOf(song(1, "/a.mp3", "Alpha"), song(2, "/b.mp3", "Bravo"))
        rescanLibraryAs(*library.toTypedArray())
        repo.addSongs(pl, library)
        assertEquals(listOf("Alpha", "Bravo"), repo.resolve(pl).map { it.song?.title })
    }

    /**
     * THE test the first round was missing, and the reason `stableKey` exists.
     *
     * A real rescan: the scanner clears the `songs` table and reinserts, so `/a.mp3` — the same
     * file, untouched — comes back under MediaStore `_ID` 7 instead of 3. Nothing about the entry
     * changed; the library's idea of the id did.
     *
     * With `sourceKey` keyed on the playable uri (`content://.../3`) rung 1 misses and rung 2
     * misses too (id 3 no longer exists), and the entry goes permanently blank. Keyed on the file
     * path it re-links, and the corrected id is cached for next time.
     */
    @Test fun `an entry survives a rescan that reissues its MediaStore id`() = runTest {
        val pl = repo.create("Mix")
        rescanLibraryAs(song(3, "/a.mp3", "Alpha"))
        repo.addSongs(pl, listOf(song(3, "/a.mp3", "Alpha")))
        assertEquals(3L, dao.getEntries(pl).single().songId)

        // the scan runs: same file, new id, and the old id is simply gone
        rescanLibraryAs(song(7, "/a.mp3", "Alpha"))

        val track = repo.resolve(pl).single()
        assertNotNull("a rescan must not blank an entry whose file never moved", track.song)
        assertEquals("Alpha", track.song?.title)
        assertEquals(7L, track.song?.id)
        assertEquals("the corrected id must be written back", 7L, dao.getEntries(pl).single().songId)
    }

    /**
     * The case that used to be silent, and the whole point of this round.
     *
     * Some providers withhold `DATA` on API 29+, so `filePath` is null. Before `relativeKey`
     * existed those rows fell all the way through to the content:// uri — the exact key we
     * removed for embedding `_ID` — and nothing surfaced it: the entry was written, looked fine,
     * and went blank at the next rescan.
     *
     * Same rescan as the test above, but the row has no file path at all.
     */
    @Test fun `an entry keyed without a DATA path still survives an id reissue`() = runTest {
        val pl = repo.create("Mix")
        rescanLibraryAs(songWithoutDataPath(3, "external_primary:Music/a.mp3", "Alpha"))
        repo.addSongs(pl, listOf(songWithoutDataPath(3, "external_primary:Music/a.mp3", "Alpha")))

        val entry = dao.getEntries(pl).single()
        assertEquals("external_primary:Music/a.mp3", entry.sourceKey)
        assertNotEquals(
            "an entry with no DATA path must not fall back to the id-bearing uri",
            "content://media/external/audio/media/3",
            entry.sourceKey,
        )

        rescanLibraryAs(songWithoutDataPath(7, "external_primary:Music/a.mp3", "Alpha"))

        val track = repo.resolve(pl).single()
        assertNotNull("a DATA-less entry must survive a rescan too", track.song)
        assertEquals(7L, track.song?.id)
        assertEquals(7L, dao.getEntries(pl).single().songId)
    }

    /**
     * The same DATA-less rescan, but wired to the **real** [LocalSource] instead of the fake.
     *
     * Every other test here uses `FakeSource`, so none of them can see a regression in the actual
     * key implementation — `LocalSourceKeysTest` guards that in isolation, and this composes the
     * two halves so the seam between them is covered too. `LocalSource.listSongs()` is never
     * reached (resolve reads Room), so no MediaStore provider is needed.
     */
    @Test fun `the real LocalSource key survives an id reissue through the repository`() = runTest {
        val real = com.kaislate.veldtplayer.data.library.LocalSource(
            ApplicationProvider.getApplicationContext()
        )
        val realRepo = PlaylistRepository(dao, songDao, SourceRegistry(setOf(real))) { ++clock }
        val pl = realRepo.create("Mix")

        // sourceId = the REAL LocalSource's own id, because that is the source in this registry.
        rescanLibraryAs(songWithoutDataPath(3, "external_primary:Music/a.mp3", "Alpha", "local"))
        realRepo.addSongs(
            pl,
            listOf(songWithoutDataPath(3, "external_primary:Music/a.mp3", "Alpha", "local")),
        )
        // `rel:` — the real LocalSource namespaces its rungs (N0 Task 5). The fakes in this file
        // do not, deliberately: the interface contract is "any string injective within a source",
        // and the per-source maps are what protect them. Only LocalSource had three rungs sharing
        // one flat space.
        assertEquals("rel:external_primary:Music/a.mp3", dao.getEntries(pl).single().sourceKey)

        rescanLibraryAs(songWithoutDataPath(7, "external_primary:Music/a.mp3", "Alpha", "local"))

        val track = realRepo.resolve(pl).single()
        assertNotNull(track.song)
        assertEquals(7L, track.song?.id)
    }

    /**
     * The cross-volume collision, end to end, with the damage it actually does.
     *
     * `RELATIVE_PATH` is volume-relative but `listSongs` queries `VOLUME_EXTERNAL`, which spans
     * internal storage and removable SD on API 29+. Two genuinely different files can sit at
     * `Music/a.mp3` on each.
     *
     * Keys are built through the REAL [LocalSource.composeRelativeKey] rather than hardcoded, so
     * this test sees a regression in the composer — that is what makes it, and not just a
     * key-equality assertion, the control for the volume qualifier.
     *
     * Drop the qualifier and both rows key alike; `resolve`'s `associateBy` keeps the last
     * (`SD Alpha`, ordered after `Internal Alpha` by title), so rung 1 returns the wrong file AND
     * `corrections` writes the wrong `songId` back. The second assertion is the important one: the
     * cache is corrupted on a READ path, so the user never took an action they could connect to it.
     */
    @Test fun `resolve does not confuse the same relative path on two storage volumes`() = runTest {
        val real = com.kaislate.veldtplayer.data.library.LocalSource(
            ApplicationProvider.getApplicationContext()
        )
        val realRepo = PlaylistRepository(dao, songDao, SourceRegistry(setOf(real))) { ++clock }
        val pl = realRepo.create("Mix")

        fun onVolume(id: Long, volume: String, title: String) = song(
            sourceId = "local", // the real LocalSource owns these rows
            id = id,
            path = null, // DATA withheld, so the volume-qualified key is the only thing standing
            title = title,
            relativeKey = com.kaislate.veldtplayer.data.library.LocalSource.composeRelativeKey(
                volumeName = volume, relativePath = "Music/", displayName = "a.mp3",
            ),
        )

        val internal = onVolume(3, "external_primary", "Internal Alpha")
        val sdCard = onVolume(9, "1234-5678", "SD Alpha")
        rescanLibraryAs(internal, sdCard)

        realRepo.addSongs(pl, listOf(internal))
        assertEquals(3L, dao.getEntries(pl).single().songId)

        val track = realRepo.resolve(pl).single()
        assertEquals("resolve returned the file from the wrong volume", "Internal Alpha", track.song?.title)
        assertEquals(3L, track.song?.id)
        assertEquals(
            "a wrong match corrupts the songId cache permanently, on a read path",
            3L,
            dao.getEntries(pl).single().songId,
        )
    }

    /**
     * An entry that resolves to nothing is RETURNED, greyed, under its imported title — never
     * dropped. Dropping it would silently shrink the user's playlist every time a volume was
     * unmounted.
     */
    @Test fun `an unresolvable entry is still returned with a null song`() = runTest {
        val pl = repo.create("Mix")
        val library = listOf(song(1, "/a.mp3", "Alpha"), song(2, "/b.mp3", "Bravo"))
        rescanLibraryAs(*library.toTypedArray())
        repo.addSongs(pl, library)
        rescanLibraryAs(song(1, "/a.mp3", "Alpha")) // b.mp3 left the library

        val tracks = repo.resolve(pl)
        assertEquals(2, tracks.size)
        assertEquals("Alpha", tracks[0].song?.title)
        assertNull(tracks[1].song)
        assertEquals("Bravo", tracks[1].entry.sourceTitle) // still renders as something
    }

    @Test fun `resolve on an empty library returns every entry unresolved`() = runTest {
        val pl = repo.create("Mix")
        val library = listOf(song(1, "/a.mp3"), song(2, "/b.mp3"))
        rescanLibraryAs(*library.toTypedArray())
        repo.addSongs(pl, library)
        rescanLibraryAs()

        val tracks = repo.resolve(pl)
        assertEquals(2, tracks.size)
        assertTrue(tracks.all { it.song == null })
        // the cached ids are NOT wiped — an unmounted volume must not destroy the fallback
        assertEquals(listOf(1L, 2L), dao.getEntries(pl).map { it.songId })
    }

    /**
     * The ladder's order, in the case where the two rungs disagree. After a rescan `/a.mp3` is id
     * 7, and id 3 has been reused by an unrelated file. The entry still caches songId = 3.
     *
     * Matching `(sourceId, sourceKey)` first yields Alpha. Matching songId first yields Bravo —
     * the wrong track, silently.
     */
    @Test fun `resolve prefers source identity over a stale songId that now points elsewhere`() =
        runTest {
            val pl = repo.create("Mix")
            rescanLibraryAs(song(3, "/a.mp3", "Alpha"))
            repo.addSongs(pl, listOf(song(3, "/a.mp3", "Alpha")))
            assertEquals(3L, dao.getEntries(pl).single().songId)

            // rescan: a.mp3 is now id 7, and id 3 has been reused by an unrelated file
            rescanLibraryAs(song(7, "/a.mp3", "Alpha"), song(3, "/b.mp3", "Bravo"))

            val track = repo.resolve(pl).single()
            assertNotNull(track.song)
            assertEquals("Alpha", track.song?.title)
            assertEquals(7L, track.song?.id)
        }

    /** The other half of the same story: the corrected id is written back, so it is a cache. */
    @Test fun `resolve writes the corrected songId back to the entry`() = runTest {
        val pl = repo.create("Mix")
        rescanLibraryAs(song(3, "/a.mp3", "Alpha"))
        repo.addSongs(pl, listOf(song(3, "/a.mp3", "Alpha")))

        rescanLibraryAs(song(7, "/a.mp3", "Alpha"), song(3, "/b.mp3", "Bravo"))
        repo.resolve(pl)

        assertEquals(7L, dao.getEntries(pl).single().songId)
        // and the returned entry already reflects the correction, not the stale read
        assertEquals(7L, repo.resolve(pl).single().entry.songId)
    }

    /** Rung 2: the key changed but the cached id still names a live song. */
    @Test fun `resolve falls back to the cached songId when the source key no longer matches`() =
        runTest {
            val pl = repo.create("Mix")
            rescanLibraryAs(song(7, "/a.mp3", "Alpha"))
            repo.addSongs(pl, listOf(song(7, "/a.mp3", "Alpha")))

            // the file moved: same MediaStore id, different path
            rescanLibraryAs(song(7, "/moved/a.mp3", "Alpha"))

            val track = repo.resolve(pl).single()
            assertEquals(7L, track.song?.id)
            assertEquals("Alpha", track.song?.title)
        }

    /**
     * The songId fallback is scoped to this source. Without the guard, a future second source's
     * ids would collide into local matches and hand back somebody else's track.
     */
    @Test fun `resolve never matches an entry belonging to a different source`() = runTest {
        val pl = repo.create("Mix")
        dao.insertEntries(
            listOf(
                PlaylistEntryEntity(
                    id = 0, playlistId = pl, position = 0,
                    sourceId = "remote", sourceKey = "/a.mp3", songId = 1L,
                    sourceTitle = "Remote Alpha", sourceArtist = "R", sourceAlbum = "R",
                )
            )
        )
        rescanLibraryAs(song(1, "/a.mp3", "Local Alpha"))

        val track = repo.resolve(pl).single()
        assertNull("a remote entry must not resolve against the local library", track.song)
        assertEquals("Remote Alpha", track.entry.sourceTitle)
    }

    /**
     * The scanner's tag merge lands in the Room row, not in MediaStore. Resolving against a live
     * source enumeration would show the untagged title here and the tagged one on the album
     * screen — the same track under two names.
     */
    @Test fun `resolve returns the tag-merged Room row, not the source's own view`() = runTest {
        val pl = repo.create("Mix")
        rescanLibraryAs(song(1, "/a.mp3", "Untagged filename"))
        repo.addSongs(pl, listOf(song(1, "/a.mp3", "Untagged filename")))

        // the scanner re-writes the row with the title read from the file's tags
        rescanLibraryAs(song(1, "/a.mp3", "Proper Tagged Title"))

        assertEquals("Proper Tagged Title", repo.resolve(pl).single().song?.title)
        // the entry keeps what it was imported as, for when it stops resolving
        assertEquals("Untagged filename", dao.getEntries(pl).single().sourceTitle)
    }

    @Test fun `resolve on an empty playlist returns an empty list`() = runTest {
        assertEquals(emptyList<PlaylistTrack>(), repo.resolve(repo.create("Mix")))
    }

    // ---- resolve: repairing a key that went stale under a preserved id ------------------------

    private val here = "external_primary:Music/a.mp3"
    private val there = "external_primary:Podcasts/a.mp3"

    /**
     * A moved file keeps its MediaStore `_ID`, so the scan re-upserts the SAME row with a new
     * location. Rung 1 misses on the entry's old key; rung 2 carries it home on the cached id.
     *
     * The entry's `sourceKey` must be re-pointed at the new location. Left stale it is inert — the
     * entry now hangs entirely off a cached id, which is the one thing the ladder was built not to
     * depend on. See the interleaving test below for what that costs.
     */
    @Test fun `resolve rewrites a stale source key when the cached id still finds the file`() =
        runTest {
            val pl = repo.create("Mix")
            rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))
            repo.addSongs(pl, listOf(songWithoutDataPath(7, here, "Alpha")))
            assertEquals(here, dao.getEntries(pl).single().sourceKey)

            // the file moved. Same id, same mtime — only the location changed.
            rescanLibraryAs(songWithoutDataPath(7, there, "Alpha"))

            val track = repo.resolve(pl).single()
            assertEquals(7L, track.song?.id)
            assertEquals("the stored key must be re-pointed at where the file now is", there,
                dao.getEntries(pl).single().sourceKey)
            // and the returned entry reflects the repair, not the stale read it started from
            assertEquals(there, track.entry.sourceKey)
        }

    /**
     * **The interleaving the whole of Step 3 exists for.** Steps 1–2 alone do not survive it.
     *
     * 1. The file moves. Its id is preserved, so `resolve` reaches it via rung 2 and — before this
     *    change — wrote nothing, because a rung-2 hit is looked up BY `entry.songId` and therefore
     *    always agrees with it. The key stayed stale forever.
     * 2. Later the id is reissued: a remount, a MediaStore rebuild. Precisely the scenario rung 1
     *    was written for.
     *
     * With the key repaired at step 1 the entry rides step 2 out on rung 1. Without it, rung 1
     * misses on the old location and rung 2 chases an id that no longer exists — the entry goes
     * permanently blank, and no user action anywhere is connected to it.
     */
    @Test fun `an entry that moved then had its id reissued still resolves`() = runTest {
        val pl = repo.create("Mix")
        rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))
        repo.addSongs(pl, listOf(songWithoutDataPath(7, here, "Alpha")))

        // (1) the move: id preserved, location changed
        rescanLibraryAs(songWithoutDataPath(7, there, "Alpha"))
        assertEquals(7L, repo.resolve(pl).single().song?.id)

        // (2) the id reissue: same file, same location, brand-new id, and 7 is simply gone
        rescanLibraryAs(songWithoutDataPath(21, there, "Alpha"))

        val track = repo.resolve(pl).single()
        assertNotNull(
            "a move followed by an id reissue must not blank the entry",
            track.song,
        )
        assertEquals(21L, track.song?.id)
        assertEquals("Alpha", track.song?.title)
        assertEquals(21L, dao.getEntries(pl).single().songId)
    }

    /**
     * The convergence property, counted rather than asserted in prose.
     *
     * `resolve` is re-entered by `PlaylistViewModel`'s `mapLatest` on its own writes, so a write
     * condition that can fire twice on unchanged input is an infinite recomposition loop. The
     * repair writes `stableKey(song)` for a song taken from the very map rung 1 searched, so the
     * next pass hits rung 1 and cannot fire again.
     */
    @Test fun `resolve quiesces after repairing a moved entry - it writes exactly once`() =
        runTest {
            val counting = CountingDao(dao)
            val convergingRepo = PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
            val pl = convergingRepo.create("Mix")
            rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))
            convergingRepo.addSongs(pl, listOf(songWithoutDataPath(7, here, "Alpha")))
            rescanLibraryAs(songWithoutDataPath(7, there, "Alpha"))

            counting.reset()
            convergingRepo.resolve(pl)
            assertEquals("the repair pass writes the key once", 1, counting.keyWrites)
            assertEquals("a rung-2 hit never disagrees on the id", 0, counting.idWrites)

            counting.reset()
            convergingRepo.resolve(pl)
            assertEquals("a second resolve on unchanged input must write nothing", 0, counting.keyWrites)
            assertEquals(0, counting.idWrites)

            // a third pass, because a two-state oscillation would show a zero on the second
            counting.reset()
            convergingRepo.resolve(pl)
            assertEquals(0, counting.keyWrites + counting.idWrites)
        }

    /**
     * Convergence in the nastier case: two library rows collide on one `stableKey`, so
     * `associateBy` keeps the last and rung 1 answers with a row that is NOT the one rung 2 found.
     *
     * The KDoc claims this settles in two extra bounces rather than oscillating — pass 1 writes the
     * key, pass 2 writes the id it now disagrees with, pass 3 writes nothing. This counts it.
     */
    @Test fun `resolve still quiesces when two library rows collide on one key`() = runTest {
        val counting = CountingDao(dao)
        val convergingRepo = PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
        val pl = convergingRepo.create("Mix")
        rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))
        convergingRepo.addSongs(pl, listOf(songWithoutDataPath(7, here, "Alpha")))

        // the file moved onto a key another row already occupies; getAllSongs orders by title, so
        // associateBy keeps "Zulu" — deliberately not the row rung 2 will find.
        rescanLibraryAs(
            songWithoutDataPath(7, there, "Alpha"),
            songWithoutDataPath(9, there, "Zulu"),
        )

        counting.reset()
        convergingRepo.resolve(pl)
        assertEquals(1, counting.keyWrites)

        counting.reset()
        val second = convergingRepo.resolve(pl)
        assertEquals("the key is not rewritten once rung 1 hits", 0, counting.keyWrites)
        assertEquals("rung 1 now answers with the colliding row, so the id is corrected once", 1, counting.idWrites)
        assertEquals("Zulu", second.single().song?.title)

        counting.reset()
        convergingRepo.resolve(pl)
        assertEquals("and then it is settled", 0, counting.keyWrites + counting.idWrites)
    }

    /**
     * The write must be scoped to "rung 1 missed". An entry that resolves on its own key is
     * already correct; rewriting it would be a write on every read, which is the endless
     * re-resolve this class's KDoc warns about.
     */
    @Test fun `resolve does not rewrite the key of an entry that matched on rung 1`() = runTest {
        val counting = CountingDao(dao)
        val quietRepo = PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
        val pl = quietRepo.create("Mix")
        rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))
        quietRepo.addSongs(pl, listOf(songWithoutDataPath(7, here, "Alpha")))

        counting.reset()
        quietRepo.resolve(pl)
        assertEquals(0, counting.keyWrites)
        assertEquals(0, counting.idWrites)
        assertEquals(here, dao.getEntries(pl).single().sourceKey)
    }

    /**
     * An `.m3u` import's unmatched entry keeps the durable text it was imported with. It has no
     * `songId`, so neither rung can hit — and inventing a key for it would destroy the one thing
     * that could ever match the file it names.
     */
    @Test fun `resolve leaves an unresolved entry's imported key alone`() = runTest {
        val counting = CountingDao(dao)
        val quietRepo = PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
        val pl = quietRepo.create("Mix")
        quietRepo.addEntries(
            pl,
            listOf(
                NewPlaylistEntry(
                    sourceId = "test-source",
                    sourceKey = "/sdcard/Music/never-scanned.mp3",
                    songId = null,
                    title = "T",
                    artist = "A",
                    album = "Al",
                ),
            ),
        )
        rescanLibraryAs(songWithoutDataPath(7, here, "Alpha"))

        counting.reset()
        val track = quietRepo.resolve(pl).single()
        assertNull(track.song)
        assertEquals(0, counting.keyWrites)
        assertEquals(
            "/sdcard/Music/never-scanned.mp3",
            dao.getEntries(pl).single().sourceKey,
        )
    }

    /** Global Constraint 2 again: the repair is scoped to entries this source owns. */
    @Test fun `resolve never rewrites the key of another source's entry`() = runTest {
        val counting = CountingDao(dao)
        val quietRepo = PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
        val pl = quietRepo.create("Mix")
        dao.insertEntries(
            listOf(
                PlaylistEntryEntity(
                    id = 0, playlistId = pl, position = 0,
                    sourceId = "remote", sourceKey = "remote://track/1", songId = 7L,
                    sourceTitle = "Remote Alpha", sourceArtist = "R", sourceAlbum = "R",
                )
            )
        )
        rescanLibraryAs(songWithoutDataPath(7, there, "Alpha"))

        counting.reset()
        assertNull(quietRepo.resolve(pl).single().song)
        assertEquals(0, counting.keyWrites)
        assertEquals("remote://track/1", dao.getEntries(pl).single().sourceKey)
    }

    @Test fun `resolve is scoped to its own playlist`() = runTest {
        val gym = repo.create("Gym")
        val chill = repo.create("Chill")
        val library = listOf(song(1, "/g.mp3", "Gymnopedie"), song(2, "/c.mp3", "Chill"))
        rescanLibraryAs(*library.toTypedArray())
        repo.addSongs(gym, listOf(library[0]))
        repo.addSongs(chill, listOf(library[1]))
        assertEquals(listOf("Gymnopedie"), repo.resolve(gym).map { it.song?.title })
        assertEquals(listOf("Chill"), repo.resolve(chill).map { it.song?.title })
    }
    // ------------------------------------------------------------------ mixed sources (N0 Task 4)

    /**
     * Two sources, one key string, two different tracks — and each entry must land on its own.
     *
     * Nothing coordinates two sources' key spaces: a Subsonic GUID and a MediaStore path are not
     * drawn from the same alphabet, but they are both `String` and they can collide. Before Task 4
     * `resolve` built ONE flat `associateBy` over every song in the table, which keeps whichever
     * row iterated last and hands it to BOTH entries. That is the P1.4 defect class exactly:
     * locally correct, silently collapses two distinct inputs.
     *
     * Asserted as a pair, so the failure message IS the collapse rather than a bare "expected 2".
     */
    @Test fun `two sources sharing one sourceKey resolve each entry to its own source's song`() = runTest {
        val alpha = FakeSource(id = "alpha")
        val beta = FakeSource(id = "beta")
        val mixedRepo =
            PlaylistRepository(dao, songDao, SourceRegistry(setOf(alpha, beta))) { ++clock }
        // Same filePath on both, so FakeSource.stableKey returns "/same.mp3" for each.
        rescanLibraryAs(
            song(1, "/same.mp3", title = "Alpha track", sourceId = "alpha"),
            song(2, "/same.mp3", title = "Beta track", sourceId = "beta"),
        )
        val pl = mixedRepo.create("Mix")
        mixedRepo.addEntries(
            pl,
            listOf(
                NewPlaylistEntry("alpha", "/same.mp3", null, "T", "A", "Al"),
                NewPlaylistEntry("beta", "/same.mp3", null, "T", "A", "Al"),
            ),
        )

        val resolved = mixedRepo.resolve(pl)
        assertEquals(
            listOf("alpha" to 1L, "beta" to 2L),
            resolved.map { it.entry.sourceId to it.song?.id },
        )
    }

    /**
     * An entry naming a source nobody registered: the removed-account state.
     *
     * It resolves to `null` — greyed, still counted, never dropped — and, critically, NOTHING is
     * written. There is no source to compute a fresh key with, and rewriting the entry would
     * destroy the identity the user needs back if they re-add the account.
     *
     * **The library row this entry caches is owned by the GHOST source itself, and that detail is
     * the whole test.** An earlier version of this test cached a row owned by the *registered*
     * source, and it passed against a deliberately broken `resolve` that fell back to an arbitrary
     * source instead of returning early — because rung 2's own `takeIf { it.sourceId ==
     * entry.sourceId }` rejected that row first. The test was green for a reason it did not name,
     * which makes it worthless as a guard on the early return. With the row owned by `ghost`, rung
     * 2 MATCHES, so the only thing standing between this entry and a resolved track — plus a
     * `sourceKey` write-back computed from a source that never described it — is the early return.
     */
    @Test fun `an entry whose source is not registered resolves to null and writes nothing`() = runTest {
        val counting = CountingDao(dao)
        val aloneRepo =
            PlaylistRepository(counting, songDao, SourceRegistry(setOf(source))) { ++clock }
        // Owned by "ghost", which the registry does NOT hold — so rung 2's source guard passes and
        // cannot be what saves us here.
        rescanLibraryAs(song(7, "/a.mp3", title = "Real row", sourceId = "ghost"))
        val pl = aloneRepo.create("Mix")
        dao.insertEntries(
            listOf(
                PlaylistEntryEntity(
                    id = 0, playlistId = pl, position = 0,
                    sourceId = "ghost", sourceKey = "/a.mp3", songId = 7L,
                    sourceTitle = "T", sourceArtist = "A", sourceAlbum = "Al",
                ),
            ),
        )

        counting.reset()
        val track = aloneRepo.resolve(pl).single()
        assertNull(track.song)
        assertEquals(0, counting.idWrites + counting.keyWrites)
        // And the stored row is byte-for-byte what it was.
        val stored = dao.getEntries(pl).single()
        assertEquals(
            "ghost" to ("/a.mp3" to 7L),
            stored.sourceId to (stored.sourceKey to stored.songId),
        )
    }

    /**
     * Rung 2, guarded by source.
     *
     * Surrogate ids share ONE AUTOINCREMENT space across every source after Task 3, so a cached id
     * always names a real row — just not necessarily one belonging to this entry. Without the
     * `takeIf { it.sourceId == entry.sourceId }` guard this entry silently resolves to a different
     * source's track, which is worse than not resolving: the playlist plays the wrong song.
     */
    @Test fun `a cached songId pointing at another source's row never resolves`() = runTest {
        val alpha = FakeSource(id = "alpha")
        val beta = FakeSource(id = "beta")
        val mixedRepo =
            PlaylistRepository(dao, songDao, SourceRegistry(setOf(alpha, beta))) { ++clock }
        rescanLibraryAs(song(2, "/beta-only.mp3", title = "Beta track", sourceId = "beta"))
        val pl = mixedRepo.create("Mix")
        dao.insertEntries(
            listOf(
                PlaylistEntryEntity(
                    id = 0, playlistId = pl, position = 0,
                    // alpha's entry, a key matching nothing, and a cache pointing at beta's row.
                    sourceId = "alpha", sourceKey = "/no-such-key.mp3", songId = 2L,
                    sourceTitle = "T", sourceArtist = "A", sourceAlbum = "Al",
                ),
            ),
        )

        assertNull(mixedRepo.resolve(pl).single().song)
    }

    /**
     * One `addSongs` call, two sources — the case a search result spanning both produces.
     *
     * This is the test that replaces `song()`'s old decoy `sourceId`. A call site that reads the
     * source from anywhere but the song (an ambient field, the first registered source, a literal)
     * cannot produce two different ids here, so the pair of literals below is what pins the
     * routing.
     */
    @Test fun `addSongs stores each song's own source identity, not any single source's`() = runTest {
        val alpha = FakeSource(id = "alpha")
        val beta = FakeSource(id = "beta")
        val mixedRepo =
            PlaylistRepository(dao, songDao, SourceRegistry(setOf(alpha, beta))) { ++clock }
        val pl = mixedRepo.create("Mix")

        mixedRepo.addSongs(
            pl,
            listOf(
                song(1, "/from-alpha.mp3", sourceId = "alpha"),
                song(2, "/from-beta.mp3", sourceId = "beta"),
            ),
        )

        assertEquals(
            listOf("alpha" to "/from-alpha.mp3", "beta" to "/from-beta.mp3"),
            dao.getEntries(pl).map { it.sourceId to it.sourceKey },
        )
    }

    /**
     * A freshly-enumerated song has no surrogate, and `0` must never reach the cache.
     *
     * `Song.UNSAVED` is `0`, which is Room's "not set". Stored in `playlist_entries.songId` it
     * stops being a sentinel and starts claiming to be an id — pinning the entry to a row Room can
     * never issue, so rung 2 is dead forever and the ladder has no reason to repair it. `null` is
     * the genuinely different thing: "unknown, ask the resolver".
     */
    @Test fun `addSongs never caches an UNSAVED id`() = runTest {
        val pl = repo.create("Mix")
        repo.addSongs(pl, listOf(song(Song.UNSAVED, "/fresh.mp3")))
        assertNull(dao.getEntries(pl).single().songId)
    }

    /**
     * Two sources handing out the SAME source-native id for different tracks.
     *
     * The N0 ledger flags this as a mandatory Task 4 control, conditioned on Task 4 looping sources
     * in the scan pipeline — which it deliberately does NOT: `LibraryScanWorker` takes the
     * `@LocalLibrary` source, so there is no set to loop over and no way to hand `ScanDiffer` a
     * concatenation of two sources' rows. The hazard is structurally absent rather than merely
     * untriggered. This asserts the property the schema owes regardless — `(sourceId, externalId)`
     * is the identity, so one `externalId` under two sources is two rows and not one.
     */
    @Test fun `one externalId under two sources is two distinct rows`() = runTest {
        val shared = "ms-collide"
        songDao.upsertBySourceKey(
            listOf(
                song(1, "/a.mp3", title = "Alpha", sourceId = "alpha")
                    .copy(externalId = shared).toEntity(),
                song(2, "/b.mp3", title = "Beta", sourceId = "beta")
                    .copy(externalId = shared).toEntity(),
            ),
        )
        assertEquals(
            listOf("alpha" to "Alpha", "beta" to "Beta"),
            songDao.getAllSongs().map { it.sourceId to it.title }.sortedBy { it.first },
        )
    }
}
