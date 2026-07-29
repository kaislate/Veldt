// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
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
     * [LibrarySource.id] (Global Constraint 2).
     */
    private class FakeSource(override val id: String = "local") : LibrarySource {
        override fun resolvePlayableUri(song: Song): String = song.uri
        override fun stableKey(song: Song): String = song.filePath ?: song.uri
        override suspend fun listSongs(): List<Song> =
            error("resolve() must read the Room songs projection, not the live source")
        override suspend fun listAlbums(): List<Album> = emptyList()
        override suspend fun listArtists(): List<Artist> = emptyList()
        override suspend fun search(query: String): List<Song> = emptyList()
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
        repo = PlaylistRepository(dao, songDao, source) { ++clock }
    }

    @After fun tearDown() = db.close()

    private fun song(id: Long, path: String, title: String = "T$id") = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = path,
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

    /**
     * Replace the whole `songs` projection — i.e. what a library scan does. Clearing first is the
     * point: the scanner deletes and reinserts rows keyed on MediaStore `_ID`, so the same file
     * can come back under a different id, and that is the case the ladder exists for.
     */
    private suspend fun rescanLibraryAs(vararg songs: Song) {
        songDao.clear()
        songDao.upsertAll(songs.map { it.toEntity() })
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
        val otherRepo = PlaylistRepository(dao, songDao, other) { ++clock }
        val pl = otherRepo.create("Mix")
        otherRepo.addSongs(pl, listOf(song(1, "/a.mp3")))
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
}
