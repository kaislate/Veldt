// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.tag

import org.junit.Assert.assertEquals
import org.junit.Test

class TagMergeTest {
    private val fallback = TrackTags(
        title = "MS Title", artist = "MS Artist", album = "MS Album", albumArtist = null,
        trackNumber = null, discNumber = null, year = null, hasEmbeddedArt = false,
    )

    @Test fun nullParsed_returnsFallback() {
        assertEquals(fallback, TagMerge.merge(null, fallback))
    }

    @Test fun parsedNonBlank_winsPerField() {
        val parsed = fallback.copy(title = "Tag Title", albumArtist = "Tag AA", year = 1999)
        val m = TagMerge.merge(parsed, fallback)
        assertEquals("Tag Title", m.title)
        assertEquals("Tag AA", m.albumArtist)
        assertEquals(1999, m.year)
        assertEquals("MS Artist", m.artist) // parsed had none stronger → fallback kept
    }

    @Test fun blankParsedFields_fallBackToMediaStore() {
        val parsed = fallback.copy(title = "   ", artist = "")
        val m = TagMerge.merge(parsed, fallback)
        assertEquals("MS Title", m.title)
        assertEquals("MS Artist", m.artist)
    }

    /**
     * The device case. Some downloaders write MediaStore's `<unknown>` sentinel into the
     * ID3 frame itself, so the PARSED value carries it — non-blank, and under the old
     * `isNotBlank()` rule it beat a correctly-cleaned fallback and walked straight into
     * the database. 29 of 31 tracks on the test device arrived this way.
     */
    @Test fun sentinelParsedFields_areTreatedAsMissing() {
        val parsed = fallback.copy(artist = "<unknown>", album = "<UNKNOWN>")
        val m = TagMerge.merge(parsed, fallback)
        assertEquals("MS Artist", m.artist)
        assertEquals("MS Album", m.album)
    }

    /** Sentinel on BOTH sides means the field is simply unknown — not the literal. */
    @Test fun sentinelOnBothSides_yieldsNull() {
        val ms = fallback.copy(artist = "<unknown>")
        val parsed = fallback.copy(artist = "<unknown>")
        assertEquals(null, TagMerge.merge(parsed, ms).artist)
    }

    @Test fun parsedNumbers_winWhenPresent() {
        val parsed = fallback.copy(trackNumber = 3, discNumber = 2)
        val m = TagMerge.merge(parsed, fallback)
        assertEquals(3, m.trackNumber)
        assertEquals(2, m.discNumber)
    }

    @Test fun hasEmbeddedArt_isOr() {
        val parsed = fallback.copy(hasEmbeddedArt = true)
        assertEquals(true, TagMerge.merge(parsed, fallback).hasEmbeddedArt)
        // fallback-only art also survives
        val artInFallback = fallback.copy(hasEmbeddedArt = true)
        assertEquals(true, TagMerge.merge(fallback, artInFallback).hasEmbeddedArt)
    }
}
