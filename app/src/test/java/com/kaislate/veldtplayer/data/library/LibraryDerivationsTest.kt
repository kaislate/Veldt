package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDerivationsTest {
    private fun song(
        id: Long, title: String, artist: String, album: String, albumArtist: String? = null,
    ) = Song(
        id = id, uri = "content://media/external/audio/media/$id", filePath = null,
        title = title, artist = artist, album = album, albumArtist = albumArtist,
        trackNumber = null, discNumber = null, year = null, durationMs = 1000,
        dateModifiedSec = 0, hasEmbeddedArt = false,
    )

    @Test fun deriveAlbums_groupsByNameAndCounts() {
        val songs = listOf(
            song(1, "a", "X", "AlbumA"),
            song(2, "b", "X", "AlbumA"),
            song(3, "c", "Y", "AlbumB"),
        )
        val albums = LibraryDerivations.deriveAlbums(songs)
        assertEquals(2, albums.size)
        assertEquals(2, albums.first { it.name == "AlbumA" }.songCount)
    }

    @Test fun deriveAlbums_prefersAlbumArtistWhenPresent() {
        val songs = listOf(song(1, "a", "X", "Alb", albumArtist = "AA"))
        assertEquals("AA", LibraryDerivations.deriveAlbums(songs).single().albumArtist)
    }

    @Test fun deriveArtists_countsDistinctAlbumsAndSongs() {
        val songs = listOf(
            song(1, "a", "X", "AlbumA"),
            song(2, "b", "X", "AlbumB"),
            song(3, "c", "X", "AlbumB"),
        )
        val artist = LibraryDerivations.deriveArtists(songs).single { it.name == "X" }
        assertEquals(2, artist.albumCount)
        assertEquals(3, artist.songCount)
    }

    @Test fun derive_isCaseAndOrderStable() {
        val songs = listOf(song(2, "b", "X", "Alb"), song(1, "a", "X", "Alb"))
        assertEquals(listOf("Alb"), LibraryDerivations.deriveAlbums(songs).map { it.name })
    }

    @Test fun `albums group case-insensitively`() {
        val songs = listOf(
            song(id = 1, title = "A", album = "Abbey Road", artist = "Beatles"),
            song(id = 2, title = "B", album = "abbey road", artist = "beatles"),
            song(id = 3, title = "C", album = "ABBEY ROAD ", artist = "Beatles"),
        )
        val albums = LibraryDerivations.deriveAlbums(songs)
        assertEquals(1, albums.size)
        assertEquals(3, albums[0].songCount)
        assertEquals("abbey road", albums[0].key)
    }

    @Test fun `album display name is the first seen spelling`() {
        val songs = listOf(
            song(id = 1, title = "A", album = "Abbey Road", artist = "Beatles"),
            song(id = 2, title = "B", album = "ABBEY ROAD", artist = "Beatles"),
        )
        assertEquals("Abbey Road", LibraryDerivations.deriveAlbums(songs)[0].name)
    }

    @Test fun `artists group case-insensitively and count distinct albums by key`() {
        val songs = listOf(
            song(id = 1, title = "A", album = "One", artist = "Boards of Canada"),
            song(id = 2, title = "B", album = "ONE", artist = "boards of canada"),
            song(id = 3, title = "C", album = "Two", artist = "Boards Of Canada"),
        )
        val artists = LibraryDerivations.deriveArtists(songs)
        assertEquals(1, artists.size)
        assertEquals("Boards of Canada", artists[0].name)
        assertEquals(2, artists[0].albumCount)
        assertEquals(3, artists[0].songCount)
    }

    @Test fun `normalize trims and case folds`() {
        assertEquals("abbey road", LibraryKeys.normalize("  Abbey Road "))
        assertEquals("abbey road", LibraryKeys.normalize("ABBEY ROAD"))
        assertEquals("", LibraryKeys.normalize("   "))
    }
}
