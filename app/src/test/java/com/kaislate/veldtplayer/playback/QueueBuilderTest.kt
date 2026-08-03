// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBuilderTest {

    private fun song(id: Long) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = "/music/$id.mp3",
        relativeKey = "Music/$id.mp3",
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

    // ------------------------------------------------------------------------ append to queue

    /**
     * The one thing an append must not do is what `build` does. Asserted on WHICH tracks and in
     * what order — a size assertion would also pass for a plan that replaced the queue with three
     * different songs.
     */
    @Test fun `appending keeps what is queued and puts the new tracks after it`() {
        val plan = QueueBuilder.append(three, listOf(song(4), song(5)))!!
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), plan.songs.map { it.id })
        assertFalse("a live queue must not be restarted under the user", plan.startPlayback)
    }

    /**
     * Appending with nothing playing has no queue to append to. Doing nothing would tell the user
     * their tracks were queued while no audio started and no surface showed them.
     */
    @Test fun `appending to an empty queue starts playing it`() {
        val plan = QueueBuilder.append(emptyList(), three)!!
        assertEquals(listOf(1L, 2L, 3L), plan.songs.map { it.id })
        assertTrue(plan.startPlayback)
    }

    @Test fun `appending nothing is a no-op the caller can skip`() {
        assertNull(QueueBuilder.append(three, emptyList()))
        assertNull(QueueBuilder.append(emptyList(), emptyList()))
    }

    /** Queuing something already in the queue is deliberate, not a mistake to dedupe away. */
    @Test fun `a track already queued can be queued again`() {
        val plan = QueueBuilder.append(three, listOf(song(2)))!!
        assertEquals(listOf(1L, 2L, 3L, 2L), plan.songs.map { it.id })
    }
}
