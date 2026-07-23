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
}
