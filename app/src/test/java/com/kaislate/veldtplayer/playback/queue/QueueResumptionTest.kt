// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.google.common.util.concurrent.SettableFuture
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ExecutionException

/** What `onPlaybackResumption` answers with (spec §3), via [QueueResumption]. */
class QueueResumptionTest {

    private val restored = SettableFuture.create<SavedQueue?>()

    @Test fun `a player that holds a queue answers with it at once, without waiting for the restore`() {
        val live = queue(size = 3, index = 2, positionMs = 12L)
        val older = queue(size = 1)
        val future = QueueResumption(restored, live = { live }, latest = { older }).resume()
        assertTrue(future.isDone)
        assertSame(live, future.get())
    }

    @Test fun `an empty player waits for the restore and then answers with the latest queue`() {
        var latest: SavedQueue? = null
        val future = QueueResumption(restored, live = { null }, latest = { latest }).resume()
        assertFalse(future.isDone)
        latest = queue(size = 4, index = 1, positionMs = 99L)
        restored.set(latest)
        assertEquals(latest, future.get())
    }

    @Test fun `nothing saved fails the future, which is the opt-out`() {
        restored.set(null)
        val future = QueueResumption(restored, live = { null }, latest = { null }).resume()
        try {
            future.get()
            fail("expected the resumption future to fail")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is QueueResumption.NothingToResume)
        }
    }

    @Test fun `a queue emptied after the restore opts out too`() {
        var latest: SavedQueue? = queue(size = 2)
        restored.set(latest)
        latest = null
        val future = QueueResumption(restored, live = { null }, latest = { latest }).resume()
        try {
            future.get()
            fail("expected the resumption future to fail")
        } catch (e: ExecutionException) {
            assertTrue(e.cause is QueueResumption.NothingToResume)
        }
    }
}
