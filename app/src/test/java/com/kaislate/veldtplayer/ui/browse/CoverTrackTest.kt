// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The whole art-morph premise rests on this comparator: both ends of a morph must reach the
 * same track from differently-sorted lists, or they hold two Coil cache entries and the
 * "morph" is a swap. So both halves of the rule are pinned independently — that embedded
 * art WINS (not loses), and that the tie-break is the LOWEST id (not the highest).
 */
class CoverTrackTest {

    private fun song(id: Long, hasEmbeddedArt: Boolean = false) = Song(
        id = id, uri = "content://media/external/audio/media/$id", filePath = null, relativeKey = null,
        title = "t$id", artist = "A", album = "Alb", albumArtist = null,
        trackNumber = null, discNumber = null, year = null, durationMs = 1000,
        dateModifiedSec = 0, hasEmbeddedArt = hasEmbeddedArt,
    )

    @Test fun `empty list has no cover`() {
        assertNull(emptyList<Song>().coverTrack())
    }

    @Test fun `prefers a track carrying embedded art over a lower-id track without it`() {
        // id order alone would pick 1; the embedded-art rule must beat it.
        val songs = listOf(song(1), song(2), song(3, hasEmbeddedArt = true))
        assertEquals(3L, songs.coverTrack()?.id)
    }

    @Test fun `ties among embedded-art tracks break on the lowest id`() {
        val songs = listOf(song(9, hasEmbeddedArt = true), song(4, hasEmbeddedArt = true))
        assertEquals(4L, songs.coverTrack()?.id)
    }

    @Test fun `falls back to the lowest id when nothing carries embedded art`() {
        assertEquals(2L, listOf(song(7), song(2), song(5)).coverTrack()?.id)
    }

    /**
     * THE property the morph depends on. Shuffling stands in for the real divergence: the
     * grid sees a title-ordered library, the album page sees the same tracks in disc/track
     * order, and both must land on one song.
     */
    @Test fun `choice does not depend on list order`() {
        val songs = listOf(song(1), song(8, hasEmbeddedArt = true), song(3), song(5, hasEmbeddedArt = true))
        // 5 and 8 carry art; 5 is the lower id. Stated outright rather than read back from
        // the function under test, so the loop below compares against a fact, not itself.
        repeat(20) {
            assertEquals(5L, songs.shuffled().coverTrack()?.id)
        }
    }
}
