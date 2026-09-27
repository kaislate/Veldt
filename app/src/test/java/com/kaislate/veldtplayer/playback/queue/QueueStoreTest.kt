// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.queue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [QueueStore] (spec §3) against a real temp directory, as `ScrobbleQueueTest` does: the class's
 * whole job is file persistence, so a fake filesystem would test the fake.
 */
class QueueStoreTest {

    private val dir: File = Files.createTempDirectory("queue-store-test").toFile()
    private val file get() = File(dir, QueueStore.FILE_NAME)

    @After fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test fun `a saved queue round-trips through a second instance, every field`() {
        val saved = queue(size = 4, index = 2, positionMs = 61_500L, shuffleOrder = listOf(3, 0, 2, 1))
            .copy(repeatMode = 2)
        QueueStore(dir).write(saved)
        assertEquals(saved, QueueStore(dir).read())
    }

    @Test fun `an item without art and with a null art path round-trips`() {
        val saved = queue(size = 2, index = 1).let { q ->
            q.copy(
                items = listOf(
                    q.items[0].copy(art = null),
                    q.items[1].copy(art = SongArt(9L, "veldt://track/acct/x", null, false)),
                ),
            )
        }
        QueueStore(dir).write(saved)
        assertEquals(saved, QueueStore(dir).read())
    }

    @Test fun `a missing file reads as nothing saved`() {
        assertNull(QueueStore(dir).read())
    }

    @Test fun `a corrupt file reads as nothing saved`() {
        dir.mkdirs()
        file.writeText("{\"v\":1,\"items\":[{\"id\":", Charsets.UTF_8)
        assertNull(QueueStore(dir).read())
    }

    @Test fun `a file of another version reads as nothing saved`() {
        QueueStore(dir).write(queue(size = 2))
        file.writeText(file.readText().replace("\"v\":1", "\"v\":2"))
        assertNull(QueueStore(dir).read())
    }

    @Test fun `a malformed item is dropped and the index follows the same song`() {
        QueueStore(dir).write(queue(size = 4, index = 3, positionMs = 800L))
        // Blank the uri of item 1: an entry that could never be played again.
        val broken = file.readText().replace(
            "\"uri\":\"content://media/external/audio/media/501\"",
            "\"uri\":\"\"",
        )
        file.writeText(broken)
        val read = QueueStore(dir).read()!!
        assertEquals(listOf("local:ext-0", "local:ext-2", "local:ext-3"), read.items.map { it.mediaId })
        assertEquals(2, read.index)
        assertEquals("local:ext-3", read.current?.mediaId)
        assertEquals(800L, read.positionMs)
    }

    @Test fun `an invalid shuffle order is discarded rather than handed to the player`() {
        QueueStore(dir).write(queue(size = 3, shuffleOrder = listOf(2, 0, 1)))
        file.writeText(file.readText().replace("\"shuffleOrder\":[2,0,1]", "\"shuffleOrder\":[2,2,1]"))
        val read = QueueStore(dir).read()!!
        assertNull(read.shuffleOrder)
        assertEquals(3, read.items.size)
    }

    @Test fun `writing null or an empty queue removes the file`() {
        val store = QueueStore(dir)
        store.write(queue(size = 2))
        assertTrue(file.isFile)
        store.write(null)
        assertFalse(file.exists())
        store.write(queue(size = 2))
        store.write(queue(size = 0))
        assertFalse(file.exists())
        assertNull(store.read())
    }

    @Test fun `a write replaces the previous queue whole and leaves no temp file behind`() {
        val store = QueueStore(dir)
        store.write(queue(size = 5, index = 4))
        store.write(queue(size = 2, index = 1, positionMs = 3L))
        assertEquals(queue(size = 2, index = 1, positionMs = 3L), store.read())
        assertEquals(listOf(QueueStore.FILE_NAME), dir.list()!!.toList())
    }

    @Test fun `the store creates its directory`() {
        val nested = File(dir, "playback")
        QueueStore(nested).write(queue(size = 1))
        assertTrue(File(nested, QueueStore.FILE_NAME).isFile)
    }
}
