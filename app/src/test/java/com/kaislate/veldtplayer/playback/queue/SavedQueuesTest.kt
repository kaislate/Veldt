// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.playback.queue.QueueFixtures.item
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** [SavedQueues]: the cap window and the prune-and-move-the-index rule (spec §3). Plain JVM. */
class SavedQueuesTest {

    private fun ids(q: SavedQueue) = q.items.map { it.mediaId }

    // ---- capped ----

    @Test fun `a queue within the cap is returned unchanged`() {
        val q = queue(size = 5, index = 3, positionMs = 77L)
        assertSame(q, SavedQueues.capped(q, cap = 5))
    }

    @Test fun `the cap keeps a quarter behind the current item and the rest ahead`() {
        val q = queue(size = 100, index = 50, positionMs = 1234L)
        val capped = SavedQueues.capped(q, cap = 20)
        assertEquals(20, capped.items.size)
        // cap/4 = 5 behind: items 45..64, with the current item at window index 5.
        assertEquals((45 until 65).map { "local:ext-$it" }, ids(capped))
        assertEquals(5, capped.index)
        assertEquals("local:ext-50", capped.current?.mediaId)
        assertEquals(1234L, capped.positionMs)
    }

    @Test fun `near the start the window slides forward so it stays full`() {
        val capped = SavedQueues.capped(queue(size = 100, index = 2), cap = 20)
        assertEquals((0 until 20).map { "local:ext-$it" }, ids(capped))
        assertEquals(2, capped.index)
    }

    @Test fun `near the end the window slides back so it stays full`() {
        val capped = SavedQueues.capped(queue(size = 100, index = 98), cap = 20)
        assertEquals((80 until 100).map { "local:ext-$it" }, ids(capped))
        assertEquals(18, capped.index)
        assertEquals("local:ext-98", capped.current?.mediaId)
    }

    @Test fun `the production cap is 2000`() {
        val capped = SavedQueues.capped(queue(size = 2_500, index = 0))
        assertEquals(2_000, capped.items.size)
        assertEquals(SavedQueues.CAP, 2_000)
    }

    @Test fun `the shuffle order is rebased onto the window in its original relative order`() {
        // Items 0..9, window of 4 around index 5 -> behind = 1 -> items 4..7.
        val q = queue(size = 10, index = 5, shuffleOrder = listOf(9, 6, 0, 4, 7, 1, 5, 2, 8, 3))
        val capped = SavedQueues.capped(q, cap = 4)
        assertEquals((4 until 8).map { "local:ext-$it" }, ids(capped))
        assertEquals(listOf(2, 0, 3, 1), capped.shuffleOrder)
    }

    // ---- pruned ----

    @Test fun `a surviving current item stays current at its saved position`() {
        val q = queue(size = 5, index = 3, positionMs = 42_000L)
        val pruned = SavedQueues.pruned(q) { it.takeUnless { i -> i.mediaId == "local:ext-1" } }!!
        assertEquals(listOf("local:ext-0", "local:ext-2", "local:ext-3", "local:ext-4"), ids(pruned))
        assertEquals(2, pruned.index)
        assertEquals("local:ext-3", pruned.current?.mediaId)
        assertEquals(42_000L, pruned.positionMs)
    }

    @Test fun `a dropped current item hands over to the next survivor from the start`() {
        val q = queue(size = 5, index = 2, positionMs = 42_000L)
        val dropped = setOf("local:ext-2", "local:ext-3")
        val pruned = SavedQueues.pruned(q) { it.takeUnless { i -> i.mediaId in dropped } }!!
        assertEquals("local:ext-4", pruned.current?.mediaId)
        assertEquals(2, pruned.index)
        assertEquals(0L, pruned.positionMs)
    }

    @Test fun `a dropped current item with nothing after it hands over to the last survivor`() {
        val q = queue(size = 5, index = 3, positionMs = 9L)
        val dropped = setOf("local:ext-3", "local:ext-4")
        val pruned = SavedQueues.pruned(q) { it.takeUnless { i -> i.mediaId in dropped } }!!
        assertEquals("local:ext-2", pruned.current?.mediaId)
        assertEquals(2, pruned.index)
        assertEquals(0L, pruned.positionMs)
    }

    @Test fun `nothing surviving is null`() {
        assertNull(SavedQueues.pruned(queue(size = 3, index = 1)) { null })
    }

    @Test fun `survivors are replaced by what resolve returned`() {
        val q = queue(size = 2, index = 0)
        val pruned = SavedQueues.pruned(q) { it.copy(title = "refreshed ${it.mediaId}") }!!
        assertEquals(listOf("refreshed local:ext-0", "refreshed local:ext-1"), pruned.items.map { it.title })
    }

    @Test fun `the shuffle order is renumbered around dropped items`() {
        val q = queue(size = 4, index = 0, shuffleOrder = listOf(3, 1, 0, 2))
        val pruned = SavedQueues.pruned(q) { it.takeUnless { i -> i.mediaId == "local:ext-1" } }!!
        // Old 0,2,3 -> new 0,1,2; the order 3,(1),0,2 becomes 2,0,1.
        assertEquals(listOf(2, 0, 1), pruned.shuffleOrder)
    }

    @Test fun `an out-of-range saved index is clamped before the rule is applied`() {
        val q = queue(size = 3, index = 17, positionMs = 5L)
        val pruned = SavedQueues.pruned(q) { it }!!
        assertEquals(2, pruned.index)
        assertEquals(5L, pruned.positionMs)
    }

    // ---- isPermutation ----

    @Test fun `isPermutation accepts exactly the orders DefaultShuffleOrder accepts`() {
        assertTrue(SavedQueues.isPermutation(listOf(2, 0, 1), 3))
        assertFalse(SavedQueues.isPermutation(listOf(0, 0, 1), 3))
        assertFalse(SavedQueues.isPermutation(listOf(0, 1), 3))
        assertFalse(SavedQueues.isPermutation(listOf(0, 1, 3), 3))
        assertFalse(SavedQueues.isPermutation(listOf(-1, 0, 1), 3))
    }

    @Test fun `current is the item at the index`() {
        assertEquals(item(1), queue(size = 3, index = 1).current)
        assertNull(queue(size = 0).current)
    }
}
