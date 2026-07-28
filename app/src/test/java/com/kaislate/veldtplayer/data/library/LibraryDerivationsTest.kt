// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryDerivationsTest {
    /** Built into expectations rather than pasted as a raw control char, which is invisible. */
    private val sep = LibraryKeys.FIELD_SEPARATOR

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
        assertEquals("beatles${sep}abbey road", albums[0].key)
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

    // --- album identity is artist + title, not title alone ------------------------------

    @Test fun `albums sharing a title but not an artist stay separate`() {
        val songs = listOf(
            song(id = 1, title = "A", album = "Greatest Hits", artist = "Queen"),
            song(id = 2, title = "B", album = "Greatest Hits", artist = "ABBA"),
        )
        val albums = LibraryDerivations.deriveAlbums(songs)
        assertEquals(2, albums.size)
        assertEquals(listOf("abba${sep}greatest hits", "queen${sep}greatest hits"), albums.map { it.key })
        // Both keep the untouched display title; only the key disambiguates them.
        assertEquals(listOf("Greatest Hits", "Greatest Hits"), albums.map { it.name })
        assertEquals(listOf(1, 1), albums.map { it.songCount })
    }

    @Test fun `album key prefers albumArtist so a multi-artist album stays whole`() {
        val songs = listOf(
            song(id = 1, title = "A", artist = "Freddie Mercury", album = "Greatest Hits",
                albumArtist = "Queen"),
            song(id = 2, title = "B", artist = "Brian May", album = "Greatest Hits",
                albumArtist = "Queen"),
        )
        val album = LibraryDerivations.deriveAlbums(songs).single()
        assertEquals("queen${sep}greatest hits", album.key)
        assertEquals(2, album.songCount)
    }

    @Test fun `album stays merged when only the artist casing differs`() {
        val songs = listOf(
            song(id = 1, title = "A", album = "Kid A", artist = "Radiohead"),
            song(id = 2, title = "B", album = "Kid A", artist = "radiohead"),
            song(id = 3, title = "C", album = "Kid A", artist = " RADIOHEAD "),
        )
        val album = LibraryDerivations.deriveAlbums(songs).single()
        assertEquals("radiohead${sep}kid a", album.key)
        assertEquals(3, album.songCount)
    }

    @Test fun `artists counting distinct albums uses the compound album key`() {
        // Same album title, different artists on an artist's own page must count as two.
        val songs = listOf(
            song(id = 1, title = "A", album = "Split", artist = "X", albumArtist = "X"),
            song(id = 2, title = "B", album = "Split", artist = "X", albumArtist = "Various"),
        )
        assertEquals(2, LibraryDerivations.deriveArtists(songs).single().albumCount)
    }

    // --- keys must always be usable as a route segment ----------------------------------

    @Test fun `blank tags fall back to addressable sentinel keys`() {
        val songs = listOf(song(id = 1, title = "T", album = "   ", artist = "  "))
        // An empty key would build the route "album/", which matches no "album/{key}".
        assertEquals("unknown-album", LibraryDerivations.deriveAlbums(songs).single().key)
        assertEquals("unknown-artist", LibraryDerivations.deriveArtists(songs).single().key)
    }

    @Test fun `keys are fixed points of normalize so callers may re-normalize safely`() {
        // A blank album leaves the separator on the key's edge, where trim() removes it
        // (U+001F IS whitespace to the JVM). Building the key through normalize() means a
        // caller who defensively re-normalizes still matches.
        val s = song(id = 1, title = "T", album = "  ", artist = "Queen")
        assertEquals("queen", LibraryKeys.albumKey(s))
        assertEquals(LibraryKeys.albumKey(s), LibraryKeys.normalize(LibraryKeys.albumKey(s)))
        assertEquals(LibraryKeys.artistKey(s), LibraryKeys.normalize(LibraryKeys.artistKey(s)))
    }

    @Test fun `the separator survives inside a key`() {
        // If trim() ate an interior separator, every key would silently become a plain
        // concatenation and the collision class below would come back.
        val key = LibraryKeys.albumKey(song(id = 1, title = "T", album = "Live", artist = "Bob"))
        assertEquals("bob${sep}live", key)
        assertEquals(key, LibraryKeys.normalize(key))
    }

    @Test fun `differently split artist and title do not collide`() {
        // With a space separator both of these keyed to "bob dylan live" — two unrelated
        // albums merging into one tile and one detail screen.
        val songs = listOf(
            song(id = 1, title = "A", artist = "Bob", album = "Dylan Live"),
            song(id = 2, title = "B", artist = "Bob Dylan", album = "Live"),
        )
        val albums = LibraryDerivations.deriveAlbums(songs)
        assertEquals(2, albums.size)
        assertEquals(
            setOf("bob${sep}dylan live", "bob dylan${sep}live"),
            albums.map { it.key }.toSet(),
        )
    }

    // --- grid ordering ------------------------------------------------------------------

    @Test fun `albums sort by title, not by the artist-led key`() {
        val songs = listOf(
            song(id = 1, title = "a", artist = "Zappa", album = "Apostrophe"),
            song(id = 2, title = "b", artist = "ABBA", album = "Waterloo"),
        )
        // Key order would put ABBA's record first; the grid is scanned by title, so the
        // titles are what must be in order.
        assertEquals(
            listOf("Apostrophe", "Waterloo"),
            LibraryDerivations.deriveAlbums(songs).map { it.name },
        )
    }

    @Test fun `album title order is case-insensitive and ties break on the key`() {
        val songs = listOf(
            song(id = 1, title = "a", artist = "Queen", album = "greatest hits"),
            song(id = 2, title = "b", artist = "ABBA", album = "Greatest Hits"),
            song(id = 3, title = "c", artist = "X", album = "Bends"),
            // "apple" vs "Bends" is the pair that PINS the fold: raw ASCII puts every
            // capital ahead of every lowercase letter, so an unfolded comparator orders
            // these Bends-then-apple. Without this pair the assertion below stays green
            // with the fold deleted, and the fold could be refactored away unnoticed.
            song(id = 4, title = "d", artist = "Y", album = "apple"),
        )
        val albums = LibraryDerivations.deriveAlbums(songs)
        assertEquals(
            listOf("apple", "Bends", "Greatest Hits", "greatest hits"),
            albums.map { it.name },
        )
        // The two same-titled records land adjacent, ordered by their differing owners.
        assertEquals(
            listOf("abba${sep}greatest hits", "queen${sep}greatest hits"),
            albums.drop(2).map { it.key },
        )
    }

    @Test fun `untitled albums sort last, like untagged tracks`() {
        val songs = listOf(
            song(id = 1, title = "a", artist = "X", album = "  "),
            song(id = 2, title = "b", artist = "X", album = "Zoo"),
            song(id = 3, title = "c", artist = "X", album = "Apple"),
        )
        assertEquals(
            listOf("Apple", "Zoo", ""),
            LibraryDerivations.deriveAlbums(songs).map { it.name },
        )
    }

    @Test fun `artists come out alphabetical`() {
        val songs = listOf(
            song(id = 1, title = "a", artist = "Zappa", album = "One"),
            song(id = 2, title = "b", artist = "aphex twin", album = "Two"),
        )
        assertEquals(
            listOf("aphex twin", "Zappa"),
            LibraryDerivations.deriveArtists(songs).map { it.name },
        )
    }

    // --- track ordering -----------------------------------------------------------------

    @Test fun `sortAlbumTracks orders by disc then track, untagged tracks last`() {
        val songs = listOf(
            song(id = 1, title = "zeta", artist = "X", album = "Alb")
                .copy(discNumber = 2, trackNumber = 1),
            song(id = 2, title = "alpha", artist = "X", album = "Alb"), // no disc, no track
            song(id = 3, title = "beta", artist = "X", album = "Alb")
                .copy(discNumber = 1, trackNumber = 2),
            song(id = 4, title = "gamma", artist = "X", album = "Alb")
                .copy(trackNumber = 1), // null disc must read as disc 1
        )
        assertEquals(
            listOf("gamma", "beta", "alpha", "zeta"),
            LibraryDerivations.sortAlbumTracks(songs).map { it.title },
        )
    }

    @Test fun `sortAlbumTracks falls back to a case-insensitive title`() {
        val songs = listOf(
            song(id = 1, title = "Beta", artist = "X", album = "Alb"),
            song(id = 2, title = "alpha", artist = "X", album = "Alb"),
        )
        // Raw ASCII would order "Beta" before "alpha"; the fold is what makes this pass.
        assertEquals(
            listOf("alpha", "Beta"),
            LibraryDerivations.sortAlbumTracks(songs).map { it.title },
        )
    }

    @Test fun `sortArtistTracks keeps each album contiguous and in track order`() {
        val songs = listOf(
            song(id = 1, title = "z2", artist = "X", album = "Zebra").copy(trackNumber = 2),
            song(id = 2, title = "a1", artist = "X", album = "Apple").copy(trackNumber = 1),
            song(id = 3, title = "z1", artist = "X", album = "Zebra").copy(trackNumber = 1),
            song(id = 4, title = "a2", artist = "X", album = "Apple").copy(trackNumber = 2),
        )
        assertEquals(
            listOf("a1", "a2", "z1", "z2"),
            LibraryDerivations.sortArtistTracks(songs).map { it.title },
        )
    }
}
