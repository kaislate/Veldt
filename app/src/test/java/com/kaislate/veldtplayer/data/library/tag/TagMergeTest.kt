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
