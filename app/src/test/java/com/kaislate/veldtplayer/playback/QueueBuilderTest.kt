// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBuilderTest {

    private fun song(id: Long) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = "/music/$id.mp3",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 180_000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private val three = listOf(song(1), song(2), song(3))

    @Test fun `plays the whole list starting at the tapped index`() {
        val plan = QueueBuilder.build(three, tappedIndex = 1)
        assertEquals(three, plan.songs)
        assertEquals(1, plan.startIndex)
    }

    @Test fun `first and last indexes are honoured`() {
        assertEquals(0, QueueBuilder.build(three, 0).startIndex)
        assertEquals(2, QueueBuilder.build(three, 2).startIndex)
    }

    @Test fun `index past the end is clamped to the last track`() {
        assertEquals(2, QueueBuilder.build(three, 99).startIndex)
    }

    @Test fun `negative index is clamped to the first track`() {
        assertEquals(0, QueueBuilder.build(three, -5).startIndex)
    }

    @Test fun `empty input yields an empty plan that callers can skip`() {
        val plan = QueueBuilder.build(emptyList(), 3)
        assertTrue(plan.songs.isEmpty())
        assertEquals(0, plan.startIndex)
    }
}
