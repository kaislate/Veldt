// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.playback.scrobble.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Test

/** [QueueSaveScheduler]'s timing rules (spec §3) against a hand-advanced clock. */
class QueueSaveSchedulerTest {

    /** A [Scheduler] whose time moves only when [advance] says so. */
    private class FakeScheduler : Scheduler {
        private data class Task(val at: Long, val seq: Int, val action: () -> Unit)
        private val tasks = mutableListOf<Task>()
        private var seq = 0
        var now = 0L
            private set

        override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMs, seq++, action)
            tasks += task
            return { tasks.remove(task) }
        }

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { it.at <= until }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
                tasks.remove(next)
                now = next.at
                next.action()
            }
            now = until
        }
    }

    private val clock = FakeScheduler()
    private var saves = 0
    private val saver = QueueSaveScheduler(clock, save = { saves++ }, debounceMs = 1_000L, periodMs = 15_000L)

    @Test fun `a burst of requests becomes one save a debounce after the last`() {
        saver.requestSave()
        clock.advance(600)
        saver.requestSave()
        clock.advance(600)
        saver.requestSave()
        clock.advance(999)
        assertEquals(0, saves)
        clock.advance(1)
        assertEquals(1, saves)
        clock.advance(10_000)
        assertEquals(1, saves)
    }

    @Test fun `playing saves every period, and pausing stops it`() {
        saver.setPlaying(true)
        clock.advance(14_999)
        assertEquals(0, saves)
        clock.advance(1)
        assertEquals(1, saves)
        clock.advance(30_000)
        assertEquals(3, saves)
        saver.setPlaying(false)
        clock.advance(60_000)
        assertEquals(3, saves)
    }

    @Test fun `a repeated playing report does not start a second periodic save`() {
        saver.setPlaying(true)
        saver.setPlaying(true)
        clock.advance(15_000)
        assertEquals(1, saves)
    }

    @Test fun `flush saves now and swallows the pending debounce`() {
        saver.requestSave()
        saver.flush()
        assertEquals(1, saves)
        clock.advance(5_000)
        assertEquals(1, saves)
    }

    @Test fun `after release nothing saves, including what was already scheduled`() {
        saver.requestSave()
        saver.setPlaying(true)
        saver.release()
        saver.requestSave()
        saver.flush()
        clock.advance(60_000)
        assertEquals(0, saves)
    }
}
