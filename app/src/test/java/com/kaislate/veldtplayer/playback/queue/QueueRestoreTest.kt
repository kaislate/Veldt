// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.item
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.queue
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [QueueRestore]: which saved items survive a restore, and what they are rebuilt from (spec §3). */
class QueueRestoreTest {

    private fun library(vararg songs: Song): Map<TrackRef, Song> =
        songs.associateBy { TrackRef(it.sourceId, it.externalId) }

    private val uriOf: (Song) -> String = { it.uri }

    @Test fun `items whose rows are gone are dropped and the index follows the current song`() {
        val saved = queue(size = 5, index = 3, positionMs = 30_000L)
        // Rows 0 and 2 are gone (deleted files, rescanned away).
        val restored = QueueRestore.restorable(saved, library(song(1), song(3), song(4)), uriOf)!!
        assertEquals(listOf("local:ext-1", "local:ext-3", "local:ext-4"), restored.items.map { it.mediaId })
        assertEquals(1, restored.index)
        assertEquals("local:ext-3", restored.current?.mediaId)
        assertEquals(30_000L, restored.positionMs)
    }

    @Test fun `a removed account's tracks are dropped like deleted files`() {
        val saved = queue(size = 2, index = 0, positionMs = 5L).let { q ->
            q.copy(items = listOf(item(0, sourceId = "acct-gone"), item(1)))
        }
        val restored = QueueRestore.restorable(saved, library(song(1)), uriOf)!!
        assertEquals(listOf("local:ext-1"), restored.items.map { it.mediaId })
        assertEquals(0, restored.index)
        assertEquals(0L, restored.positionMs)
    }

    @Test fun `survivors are rebuilt from their library rows, not kept as saved`() {
        // A destructive migration renumbered the row: surrogate 77, not the saved 2.
        val fresh = song(2, id = 77L, title = "Retagged")
        val restored = QueueRestore.restorable(queue(size = 3, index = 2), library(fresh), uriOf)!!
        val only = restored.items.single()
        assertEquals("Retagged", only.title)
        assertEquals(fresh.toSongArt(), only.art)
        assertEquals(77L, only.art?.songId)
        assertEquals(fresh.durationMs, only.durationMs)
        assertEquals(fresh.uri, only.uri)
    }

    @Test fun `nothing surviving means nothing to restore`() {
        assertNull(QueueRestore.restorable(queue(size = 3), library(), uriOf))
    }

    @Test fun `an unreadable database restores the queue exactly as saved`() {
        val saved = queue(size = 3, index = 1, positionMs = 9L)
        assertSame(saved, QueueRestore.restorable(saved, null, uriOf))
    }

    @Test fun `a source that fails to produce a playable uri drops that item`() {
        val saved = queue(size = 2, index = 0)
        val restored = QueueRestore.restorable(saved, library(song(0), song(1))) {
            if (it.externalId == "ext-0") error("no LibrarySource registered") else it.uri
        }!!
        assertEquals(listOf("local:ext-1"), restored.items.map { it.mediaId })
    }

    @Test fun `refsOf names every parsable saved mediaId`() {
        val saved = queue(size = 2).let { q -> q.copy(items = q.items + item(9).copy(mediaId = "no-colon")) }
        assertEquals(setOf(TrackRef("local", "ext-0"), TrackRef("local", "ext-1")), QueueRestore.refsOf(saved))
    }
}
