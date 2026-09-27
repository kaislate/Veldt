// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.browse.BrowseTree.Companion.page
import com.kaislate.veldtplayer.playback.queue.QueueFixtures
import com.kaislate.veldtplayer.playback.queue.SavedQueue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse tree (spec §6) as a pure function of a fixed catalog: its structure, paging, search,
 * and what a mediaId or a voice query sent back by a car resolves to. Plain JVM.
 */
class BrowseTreeTest {

    private fun song(
        n: Int,
        title: String,
        artist: String,
        album: String,
        track: Int? = null,
        sourceId: String = "local",
    ) = Song(
        id = n.toLong(),
        sourceId = sourceId,
        externalId = "x$n",
        uri = "content://media/external/audio/media/${n + 700}",
        filePath = "/music/$n.mp3",
        relativeKey = "external_primary:Music/$n.mp3",
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = track,
        discNumber = 1,
        year = null,
        durationMs = 60_000L + n,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    // Two albums by Beck (Sea Change in track order 1..3, Odelay), one Björk, one server track.
    private val lostCause = song(1, "Lost Cause", "Beck", "Sea Change", track = 3)
    private val goldenAge = song(2, "The Golden Age", "Beck", "Sea Change", track = 1)
    private val paperTiger = song(3, "Paper Tiger", "Beck", "Sea Change", track = 2)
    private val devilsHaircut = song(4, "Devils Haircut", "Beck", "Odelay", track = 1)
    private val joga = song(5, "Joga", "Björk", "Homogenic", track = 1)
    private val streamed = song(6, "Army of Me", "Björk", "Post", track = 1, sourceId = "acct")

    private class FakeCatalog(
        var songs: List<Song>,
        val playlists: Map<PlaylistRef, List<Song>> = emptyMap(),
        var recent: SavedQueue? = null,
    ) : BrowseCatalog {
        override suspend fun songs() = songs
        override suspend fun playlists() = playlists.keys.toList()
        override suspend fun playlistSongs(id: Long) = playlists.entries.first { it.key.id == id }.value
        override suspend fun recent() = recent
    }

    private val roadTrip = PlaylistRef(7L, "Road Trip")
    private val catalog = FakeCatalog(
        songs = listOf(lostCause, goldenAge, paperTiger, devilsHaircut, joga, streamed),
        playlists = mapOf(roadTrip to listOf(joga, lostCause)),
    )
    private val tree = BrowseTree(catalog)

    private fun titles(nodes: List<BrowseNode>?) = nodes!!.map { it.title }

    // ---------- structure ----------

    @Test fun `the root lists Recent, Playlists, Albums, Artists and Songs, all browsable`() = runTest {
        val root = tree.children(BrowseIds.ROOT, 0, 100)!!
        assertEquals(
            listOf(BrowseIds.RECENT, BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS),
            root.map { it.id },
        )
        assertEquals(listOf("Recent", "Playlists", "Albums", "Artists", "Songs"), titles(root))
        assertTrue(root.all { it.browsable && !it.playable })
    }

    @Test fun `a controller that shows four root tabs gets them without Recent`() = runTest {
        val root = tree.children(BrowseIds.ROOT, 0, 100, rootLimit = 4)!!
        assertEquals(listOf(BrowseIds.PLAYLISTS, BrowseIds.ALBUMS, BrowseIds.ARTISTS, BrowseIds.SONGS), root.map { it.id })
    }

    @Test fun `Songs is every visible song A to Z, as playable leaves`() = runTest {
        val songs = tree.children(BrowseIds.SONGS, 0, 100)!!
        assertEquals(
            listOf("Army of Me", "Devils Haircut", "Joga", "Lost Cause", "Paper Tiger", "The Golden Age"),
            titles(songs),
        )
        assertTrue(songs.all { it.playable && !it.browsable && it.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC })
        assertEquals("Björk", songs.first().subtitle)
        assertEquals(streamed.durationMs, songs.first().durationMs)
        assertEquals(streamed.id, songs.first().art?.songId)
    }

    @Test fun `Songs is paged by the caller's page and page size`() = runTest {
        assertEquals(listOf("Joga", "Lost Cause"), titles(tree.children(BrowseIds.SONGS, 1, 2)))
        assertEquals(listOf("Paper Tiger", "The Golden Age"), titles(tree.children(BrowseIds.SONGS, 2, 2)))
        assertEquals(emptyList<String>(), titles(tree.children(BrowseIds.SONGS, 3, 2)))
    }

    @Test fun `paging past Int range does not wrap around to the first page`() {
        val list = listOf(1, 2, 3)
        assertEquals(emptyList<Int>(), list.page(3, Int.MAX_VALUE))
        assertEquals(listOf(1, 2, 3), list.page(0, Int.MAX_VALUE))
        assertEquals(emptyList<Int>(), list.page(-1, 2))
        assertEquals(emptyList<Int>(), list.page(0, 0))
    }

    @Test fun `Albums lists each album once, and an album's tracks come in track order`() = runTest {
        val albums = tree.children(BrowseIds.ALBUMS, 0, 100)!!
        assertEquals(listOf("Homogenic", "Odelay", "Post", "Sea Change"), titles(albums).sorted())
        val seaChange = albums.single { it.title == "Sea Change" }
        assertEquals("Beck", seaChange.subtitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_ALBUM, seaChange.mediaType)
        // The album's art is its first track's.
        assertEquals(goldenAge.id, seaChange.art?.songId)
        val tracks = tree.children(seaChange.id, 0, 100)!!
        assertEquals(listOf("The Golden Age", "Paper Tiger", "Lost Cause"), titles(tracks))
    }

    @Test fun `Artists lists each artist with their songs grouped by album`() = runTest {
        val artists = tree.children(BrowseIds.ARTISTS, 0, 100)!!
        assertEquals(listOf("Beck", "Björk"), titles(artists))
        assertEquals("4 songs", artists[0].subtitle)
        val beck = tree.children(artists[0].id, 0, 100)!!
        assertEquals(listOf("Devils Haircut", "The Golden Age", "Paper Tiger", "Lost Cause"), titles(beck))
    }

    @Test fun `Playlists lists playlists and a playlist's tracks in playlist order`() = runTest {
        val lists = tree.children(BrowseIds.PLAYLISTS, 0, 100)!!
        assertEquals(listOf("Road Trip"), titles(lists))
        assertEquals(BrowseIds.playlist(7L), lists.single().id)
        assertEquals(listOf("Joga", "Lost Cause"), titles(tree.children(lists.single().id, 0, 100)))
    }

    @Test fun `Recent is the saved queue, and the recent root holds only its current item`() = runTest {
        assertNull(tree.recentRoot())
        catalog.recent = QueueFixtures.queue(size = 3, index = 1, positionMs = 9_000L)
        assertEquals(listOf("Title 0", "Title 1", "Title 2"), titles(tree.children(BrowseIds.RECENT, 0, 100)))
        assertEquals(BrowseIds.RECENT_ROOT, tree.recentRoot()?.id)
        val resume = tree.children(BrowseIds.RECENT_ROOT, 0, 100)!!.single()
        assertEquals("Title 1", resume.title)
        assertTrue(resume.playable)
        assertEquals(QueueFixtures.item(1).art, resume.art)
    }

    @Test fun `an unknown parent, and an album that no longer exists, are not browsable`() = runTest {
        assertNull(tree.children("nonsense", 0, 10))
        assertNull(tree.children(BrowseIds.album("no such album"), 0, 10))
        assertNull(tree.children(BrowseIds.playlist(99L), 0, 10))
    }

    @Test fun `item finds categories, containers and leaves, and nothing for unknown ids`() = runTest {
        assertEquals("Albums", tree.item(BrowseIds.ALBUMS)?.title)
        val leaf = tree.children(BrowseIds.SONGS, 0, 100)!!.single { it.title == "Joga" }
        assertEquals(leaf, tree.item(leaf.id))
        assertEquals("Road Trip", tree.item(BrowseIds.playlist(7L))?.title)
        assertNull(tree.item("veldt.nothing"))
        assertNull(tree.item(BrowseIds.leaf(BrowseIds.SONGS, 0, "local:gone")))
    }

    // ---------- search ----------

    @Test fun `search ranks title matches before artist matches before album matches`() = runTest {
        catalog.songs = catalog.songs + song(8, "Beckoning", "Someone", "Else")
        val results = tree.search("beck", 0, 100)
        assertEquals("Beckoning", results.first().title)
        assertEquals(5, tree.searchCount("beck"))
        assertEquals(setOf("Lost Cause", "The Golden Age", "Paper Tiger", "Devils Haircut"), titles(results.drop(1)).toSet())
        assertEquals(listOf("Joga"), titles(tree.search("HOMOGENIC", 0, 100)))
        assertEquals(0, tree.searchCount("   "))
    }

    // ---------- resolution ----------

    @Test fun `a browsed leaf plays its whole album from that track`() = runTest {
        val seaChange = tree.children(BrowseIds.ALBUMS, 0, 100)!!.single { it.title == "Sea Change" }
        val album = tree.children(seaChange.id, 0, 100)!!
        val paper = album.single { it.title == "Paper Tiger" }
        val plan = tree.resolvePlay(listOf(PlayRequest(paper.id)), C.INDEX_UNSET, C.TIME_UNSET)!!
        assertEquals(listOf(goldenAge, paperTiger, lostCause), plan.entries.map { (it as PlayEntry.Library).song })
        assertEquals(1, plan.startIndex)
        assertEquals(0L, plan.startPositionMs)
    }

    @Test fun `a leaf still finds its track after the library changed under it`() = runTest {
        val leaf = tree.children(BrowseIds.SONGS, 0, 100)!!.single { it.title == "Joga" }
        catalog.songs = listOf(song(9, "Aardvark", "A", "A")) + catalog.songs
        val plan = tree.resolvePlay(listOf(PlayRequest(leaf.id)), 0, C.TIME_UNSET)!!
        assertEquals(joga, (plan.entries[plan.startIndex] as PlayEntry.Library).song)
        assertEquals(3, plan.startIndex)
    }

    @Test fun `a Recent leaf resumes the saved queue, at the saved position for the saved item`() = runTest {
        catalog.recent = QueueFixtures.queue(size = 3, index = 1, positionMs = 9_000L)
        val leaves = tree.children(BrowseIds.RECENT, 0, 100)!!
        val current = tree.resolvePlay(listOf(PlayRequest(leaves[1].id)), 0, C.TIME_UNSET)!!
        assertEquals(1, current.startIndex)
        assertEquals(9_000L, current.startPositionMs)
        assertEquals(QueueFixtures.item(0), (current.entries[0] as PlayEntry.Saved).item)
        val other = tree.resolvePlay(listOf(PlayRequest(leaves[2].id)), 0, C.TIME_UNSET)!!
        assertEquals(2, other.startIndex)
        assertEquals(0L, other.startPositionMs)
    }

    @Test fun `a bare session mediaId plays that one track, and an unknown one plays nothing`() = runTest {
        val plan = tree.resolvePlay(listOf(PlayRequest("acct:x6")), 0, C.TIME_UNSET)!!
        assertEquals(listOf<PlayEntry>(PlayEntry.Library(streamed)), plan.entries)
        assertNull(tree.resolvePlay(listOf(PlayRequest("acct:missing")), 0, C.TIME_UNSET))
    }

    @Test fun `a container id plays the container from the top`() = runTest {
        val plan = tree.resolvePlay(listOf(PlayRequest(BrowseIds.playlist(7L))), 0, C.TIME_UNSET)!!
        assertEquals(listOf(joga, lostCause), plan.entries.map { (it as PlayEntry.Library).song })
        assertEquals(0, plan.startIndex)
    }

    @Test fun `items that already carry a uri pass through untouched with the caller's start`() = runTest {
        val plan = tree.resolvePlay(
            listOf(PlayRequest("a", hasUri = true), PlayRequest("b", hasUri = true)),
            1,
            4_000L,
        )!!
        assertEquals(listOf(PlayEntry.Passthrough(0), PlayEntry.Passthrough(1)), plan.entries)
        assertEquals(1, plan.startIndex)
        assertEquals(4_000L, plan.startPositionMs)
    }

    @Test fun `several requests resolve one for one and the start moves past what did not resolve`() = runTest {
        val plan = tree.resolvePlay(
            listOf(PlayRequest("local:gone"), PlayRequest("local:x5"), PlayRequest("local:x1")),
            2,
            C.TIME_UNSET,
        )!!
        assertEquals(listOf(joga, lostCause), plan.entries.map { (it as PlayEntry.Library).song })
        assertEquals(1, plan.startIndex)
    }

    @Test fun `adding a browsed leaf adds that track alone, not its album`() = runTest {
        val leaf = tree.children(BrowseIds.SONGS, 0, 100)!!.single { it.title == "Lost Cause" }
        assertEquals(listOf<PlayEntry>(PlayEntry.Library(lostCause)), tree.resolveAdd(listOf(PlayRequest(leaf.id))))
        assertEquals(2, tree.resolveAdd(listOf(PlayRequest(BrowseIds.playlist(7L)))).size)
        assertEquals(emptyList<PlayEntry>(), tree.resolveAdd(listOf(PlayRequest("nope"))))
    }

    // ---------- voice ----------

    private suspend fun voice(query: String) =
        tree.resolvePlay(listOf(PlayRequest(mediaId = "", searchQuery = query)), C.INDEX_UNSET, C.TIME_UNSET)

    @Test fun `a voice query naming an album plays that album in track order`() = runTest {
        val plan = voice("sea change")!!
        assertEquals(listOf(goldenAge, paperTiger, lostCause), plan.entries.map { (it as PlayEntry.Library).song })
        assertEquals(0, plan.startIndex)
    }

    @Test fun `a voice query naming an artist plays that artist`() = runTest {
        val plan = voice("Björk")!!
        assertEquals(setOf(joga, streamed), plan.entries.map { (it as PlayEntry.Library).song }.toSet())
    }

    @Test fun `a voice query naming a playlist plays it`() = runTest {
        val plan = voice("road trip")!!
        assertEquals(listOf(joga, lostCause), plan.entries.map { (it as PlayEntry.Library).song })
    }

    @Test fun `a voice query naming a title plays the search results from that title`() = runTest {
        val plan = voice("paper tiger")!!
        assertEquals(paperTiger, (plan.entries[plan.startIndex] as PlayEntry.Library).song)
    }

    @Test fun `a blank voice query resumes the saved queue, else plays every song`() = runTest {
        val all = voice("")!!
        assertEquals(6, all.entries.size)
        assertEquals("Army of Me", (all.entries[0] as PlayEntry.Library).song.title)
        catalog.recent = QueueFixtures.queue(size = 2, index = 1, positionMs = 3_000L)
        val resumed = voice("  ")!!
        assertEquals(1, resumed.startIndex)
        assertEquals(3_000L, resumed.startPositionMs)
        assertTrue(resumed.entries.all { it is PlayEntry.Saved })
    }

    @Test fun `a voice query matching nothing plays nothing`() = runTest {
        assertNull(voice("zzzz"))
    }

    // ---------- ids ----------

    @Test fun `ids with separators in their free text round-trip exactly`() {
        val key = "a|b/c%d e"
        assertEquals(BrowseIds.Container.Album(key), BrowseIds.parseContainer(BrowseIds.album(key)))
        assertEquals(BrowseIds.Container.Search(key), BrowseIds.parseContainer(BrowseIds.search(key)))
        val leaf = BrowseIds.leaf(BrowseIds.album(key), 4, "acct:ext|with|bars")
        assertEquals(BrowseIds.Leaf(BrowseIds.album(key), 4, "acct:ext|with|bars"), BrowseIds.parseLeaf(leaf))
        assertNull(BrowseIds.parseLeaf("play|ctx|notanumber|id"))
        assertNull(BrowseIds.parseLeaf("play|ctx|3|"))
        assertNull(BrowseIds.parseContainer("veldt.nothing"))
        assertNotNull(BrowseIds.parseContainer(BrowseIds.SONGS))
        assertFalse(BrowseIds.album(key).contains('|'))
    }
}
