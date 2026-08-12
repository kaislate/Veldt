// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNamesTest {

    private fun song(artist: String, album: String, albumArtist: String? = null) = Song(
        id = 1, sourceId = "test-source", externalId = "ms-9001",
        uri = "content://media/external/audio/media/1", filePath = null, relativeKey = null,
        title = "T", artist = artist, album = album, albumArtist = albumArtist,
        trackNumber = null, discNumber = null, year = null, durationMs = 0,
        dateModifiedSec = 0, hasEmbeddedArt = false,
    )

    @Test fun `absent blank and whitespace tags are missing`() {
        assertTrue(DisplayNames.isMissing(null))
        assertTrue(DisplayNames.isMissing(""))
        assertTrue(DisplayNames.isMissing("   "))
    }

    /**
     * The bug this file exists for. MediaStore substitutes this LITERAL for a missing tag;
     * it is not blank, so every isBlank() check in the app read straight past it.
     */
    @Test fun `MediaStore's unknown sentinel is missing`() {
        assertTrue(DisplayNames.isMissing("<unknown>"))
        assertTrue(DisplayNames.isMissing("  <unknown>  "))
        assertTrue(DisplayNames.isMissing("<UNKNOWN>"))
    }

    @Test fun `a real tag is not missing and keeps its own spelling`() {
        assertFalse(DisplayNames.isMissing("Radiohead"))
        assertEquals("Radiohead", DisplayNames.artist("  Radiohead  "))
        // Only the exact sentinel folds away — a band that happens to contain the word does not.
        assertFalse(DisplayNames.isMissing("Unknown Mortal Orchestra"))
        assertEquals("Unknown Mortal Orchestra", DisplayNames.artist("Unknown Mortal Orchestra"))
    }

    @Test fun `tagOrNull yields null for every missing form`() {
        assertNull(DisplayNames.tagOrNull(null))
        assertNull(DisplayNames.tagOrNull(" "))
        assertNull(DisplayNames.tagOrNull("<unknown>"))
        assertEquals("Kid A", DisplayNames.tagOrNull(" Kid A "))
    }

    @Test fun `fallback names differ per field`() {
        assertEquals("Unknown title", DisplayNames.title("<unknown>"))
        assertEquals("Unknown album", DisplayNames.album("<unknown>"))
        assertEquals("Unknown artist", DisplayNames.artist("<unknown>"))
    }

    @Test fun `album artist falls back to the track artist, not to Unknown`() {
        assertEquals("Radiohead", DisplayNames.albumArtist(null, "Radiohead"))
        assertEquals("Radiohead", DisplayNames.albumArtist("", "Radiohead"))
        // The case the sentinel introduced: a non-blank ALBUM_ARTIST that says nothing must
        // still lose to a track artist that says something.
        assertEquals("Radiohead", DisplayNames.albumArtist("<unknown>", "Radiohead"))
        assertEquals("Various Artists", DisplayNames.albumArtist("Various Artists", "Radiohead"))
        assertEquals("Unknown artist", DisplayNames.albumArtist("<unknown>", "<unknown>"))
    }

    @Test fun `Song extensions read the same rules`() {
        val s = song(artist = "<unknown>", album = "<unknown>", albumArtist = "<unknown>")
        assertEquals("Unknown artist", s.displayArtist())
        assertEquals("Unknown album", s.displayAlbum())
        assertEquals("Unknown artist", s.displayAlbumArtist())
        assertEquals("Unknown title", song(artist = "A", album = "B").copy(title = " ").displayTitle())
    }

    /**
     * The worst symptom: every untagged file grouped under one imaginary artist, collapsing
     * the Artists tab to a single entry containing the whole library.
     */
    @Test fun `the sentinel does not become a grouping key`() {
        assertEquals(LibraryKeys.UNKNOWN_ARTIST, LibraryKeys.artistKey("<unknown>"))
        assertEquals(LibraryKeys.UNKNOWN_ARTIST, LibraryKeys.artistKey(song("<unknown>", "Alb")))
        assertEquals(LibraryKeys.UNKNOWN_ALBUM, LibraryKeys.albumKey("<unknown>", null, "<unknown>"))
        assertEquals("", LibraryKeys.normalize("<unknown>"))
    }

    @Test fun `two untagged artists do not merge with a real one`() {
        val artists = LibraryDerivations.deriveArtists(
            listOf(song("<unknown>", "A"), song("Radiohead", "B"), song("", "C"))
        )
        // The two untagged rows collapse together (they are both "no artist"), and the real
        // artist stays its own entry — the pre-fix behaviour merged all three.
        assertEquals(2, artists.size)
        assertEquals(2, artists.single { it.key == LibraryKeys.UNKNOWN_ARTIST }.songCount)
    }

    /**
     * A record whose ALBUM_ARTIST column holds the sentinel must still file under its track
     * artist, not under nobody — otherwise a correctly-tagged album lands in "Unknown".
     */
    @Test fun `a sentinel album artist loses to a real track artist in the key`() {
        assertEquals(
            LibraryKeys.albumKey("Kid A", null, "Radiohead"),
            LibraryKeys.albumKey("Kid A", "<unknown>", "Radiohead"),
        )
    }
}
