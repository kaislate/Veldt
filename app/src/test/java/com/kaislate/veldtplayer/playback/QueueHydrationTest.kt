// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** [QueueHydration]: the app's `List<Song>` for a queue it did not build (spec §3, §6). */
class QueueHydrationTest {

    private fun info(mediaId: String, title: String = "Session $mediaId", art: SongArt? = null) =
        SessionItemInfo(mediaId, title, "Some Artist", "Some Album", 180_000L, art)

    @Test fun `items with a library row become that row`() {
        val row = song(3)
        val out = QueueHydration.hydrate(listOf(info("local:ext-3")), mapOf(TrackRef("local", "ext-3") to row))
        assertSame(row, out.single())
    }

    @Test fun `an item without a row still takes its place, so later items keep their index`() {
        val row = song(2)
        val art = SongArt(songId = 41L, uri = "content://media/external/audio/media/9", filePath = "/m/9.mp3", hasEmbeddedArt = true)
        val out = QueueHydration.hydrate(
            listOf(info("acct:gone", title = "Gone", art = art), info("local:ext-2")),
            mapOf(TrackRef("local", "ext-2") to row),
        )
        assertEquals(2, out.size)
        val fallback = out[0]
        assertEquals("Gone", fallback.title)
        assertEquals("acct", fallback.sourceId)
        assertEquals("gone", fallback.externalId)
        // The art's surrogate, so now playing and Coil agree with the notification's cover.
        assertEquals(41L, fallback.id)
        assertEquals("/m/9.mp3", fallback.filePath)
        assertEquals(true, fallback.hasEmbeddedArt)
        assertEquals(180_000L, fallback.durationMs)
        assertSame(row, out[1])
    }

    @Test fun `a fallback with no art is unsaved, and an unparsable id is kept whole`() {
        val out = QueueHydration.hydrate(listOf(info("no-colon")), emptyMap()).single()
        assertEquals(Song.UNSAVED, out.id)
        assertEquals("", out.sourceId)
        assertEquals("no-colon", out.externalId)
    }

    @Test fun `SessionMediaId splits at the first colon and rejects empty halves`() {
        assertEquals(TrackRef("acct", "a:b:c"), SessionMediaId.parse("acct:a:b:c"))
        assertEquals(null, SessionMediaId.parse(":x"))
        assertEquals(null, SessionMediaId.parse("x:"))
        assertEquals(null, SessionMediaId.parse("x"))
        assertEquals("local:ext-5", SessionMediaId.of(song(5)))
    }
}
