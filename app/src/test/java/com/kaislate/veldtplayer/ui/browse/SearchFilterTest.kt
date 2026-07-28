// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The matching rule behind the search screen's Artists and Albums shelves.
 *
 * These sections are FILTERED from the catalogue rather than derived from the songs a term
 * matched, and the tests below pin what that buys: a term only ever lists an artist or a
 * record that the term actually names, while still finding a record by its owner.
 */
class SearchFilterTest {

    private fun album(title: String, owner: String?, artist: String = "Various") = Album(
        key = LibraryKeys.albumKey(title, owner, artist),
        name = title,
        albumArtist = owner,
        songCount = 1,
    )

    private fun artist(name: String) = Artist(
        key = LibraryKeys.artistKey(name),
        name = name,
        albumCount = 1,
        songCount = 1,
    )

    private val albums = listOf(
        album("Abbey Road", "The Beatles"),
        album("Revolver", null, artist = "The Beatles"),
        album("Greatest Hits", "Queen"),
        album("Greatest Hits", "ABBA"),
    )

    private val artists = listOf(artist("The Beatles"), artist("Queen"), artist("Portishead"))

    @Test fun `a blank term matches nothing, not everything`() {
        assertTrue(SearchFilter.albums(albums, "").isEmpty())
        assertTrue(SearchFilter.artists(artists, "   ").isEmpty())
    }

    @Test fun `an album is found by its title, case- and space-insensitively`() {
        assertEquals(listOf("Abbey Road"), SearchFilter.albums(albums, "  ABBEY ").map { it.name })
    }

    /**
     * The point of matching on the compound KEY rather than on the display name: one
     * containment check finds a record by its owner as well as by its title.
     */
    @Test fun `an album is found by its owner`() {
        assertEquals(
            listOf("Abbey Road", "Revolver"),
            SearchFilter.albums(albums, "beatles").map { it.name },
        )
    }

    /**
     * "Revolver" carries no ALBUM_ARTIST tag, so its key's owner came from the track
     * artist. Searching the band must still find it — this is the case a filter written
     * against `Album.albumArtist` alone would silently drop.
     */
    @Test fun `an untagged album artist still matches through the track artist`() {
        assertEquals(listOf("Revolver"), SearchFilter.albums(listOf(albums[1]), "beatles").map { it.name })
    }

    @Test fun `two same-titled records both match their shared title`() {
        val hits = SearchFilter.albums(albums, "greatest hits")
        assertEquals(2, hits.size)
        // Distinct keys, so the shelf shows two cards rather than collapsing them into one.
        assertEquals(2, hits.map { it.key }.distinct().size)
    }

    @Test fun `an artist is found by a fragment of the name`() {
        assertEquals(listOf("Portishead"), SearchFilter.artists(artists, "ishe").map { it.name })
    }

    /**
     * THE reason these sections are not derived from the matched songs. "Queen" appears in
     * no Beatles metadata, so a Beatles search must not list it — which a song-derived
     * section would do the moment one matching track happened to sit on a Queen record.
     */
    @Test fun `an unrelated artist is not listed`() {
        assertEquals(listOf("The Beatles"), SearchFilter.artists(artists, "beatles").map { it.name })
    }

    @Test fun `a term that names nothing yields nothing`() {
        assertTrue(SearchFilter.albums(albums, "zzz").isEmpty())
        assertTrue(SearchFilter.artists(artists, "zzz").isEmpty())
    }
}
