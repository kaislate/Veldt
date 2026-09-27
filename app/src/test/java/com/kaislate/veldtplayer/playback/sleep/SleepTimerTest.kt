// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.sleep

import com.kaislate.veldtplayer.playback.scrobble.Scheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SleepTimer] against a virtual clock: the fade volume at exact moments, when the pause happens,
 * and what is restored. Plain JVM — the timer's whole seam is [SleepTimer.Playback], [Scheduler]
 * and a clock lambda.
 */
class SleepTimerTest {

    private var now = 1_000_000L

    /** Runs due actions in time order, moving [now] to each one's due time as it runs. */
    private inner class VirtualScheduler : Scheduler {
        private inner class Task(val due: Long, val action: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()

        override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMs, action)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceTo(t: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.due <= t }.minByOrNull { it.due } ?: break
                tasks.remove(next)
                now = next.due
                next.action()
            }
            now = t
        }

        val pending: Int get() = tasks.count { !it.cancelled }
    }

    private class FakePlayback : SleepTimer.Playback {
        override var positionMs = 0L
        override var durationMs = 0L
        override var isPlaying = true
        var pauses = 0
        var pauseAtEnd = false
        override fun pause() {
            pauses++
            isPlaying = false
        }
        override fun setPauseAtEndOfItem(enabled: Boolean) {
            pauseAtEnd = enabled
        }
    }

    private val scheduler = VirtualScheduler()
    private val playback = FakePlayback()
    private val fades = mutableListOf<Float>()
    private val updates = mutableListOf<SleepTimerStatus>()
    private val timer = SleepTimer(
        now = { now },
        scheduler = scheduler,
        playback = playback,
        fade = { fades += it },
        onUpdate = { updates += it },
    )

    private val lastFade get() = fades.last()
    private val start = now

    private fun at(msAfterStart: Long) = scheduler.advanceTo(start + msAfterStart)

    // ---- timed ----

    @Test fun `a 15 minute timer fades linearly over its last 30 s - full at 0 s, half at 15 s, silent at 30 s`() {
        timer.setMinutes(15)
        at(14 * 60_000L)
        assertEquals("a minute before the fade window: untouched", 1f, lastFade, 0f)
        at(14 * 60_000L + 30_000)
        assertEquals("t=0 s of the fade", 1f, lastFade, 1e-6f)
        at(14 * 60_000L + 45_000)
        assertEquals("t=15 s of the fade", 0.5f, lastFade, 1e-6f)
        at(14 * 60_000L + 59_900)
        assertEquals("100 ms before the end", 100f / 30_000f, lastFade, 1e-6f)
        assertEquals(0, playback.pauses)
        at(15 * 60_000L)
        // t=30 s: the last faded tick, THEN the pause, THEN the volume came back.
        assertEquals(100f / 30_000f, fades[fades.size - 2], 1e-6f)
        assertEquals(1f, fades.last(), 0f)
        assertEquals(1, playback.pauses)
        assertEquals(SleepTimerState.Off, timer.state)
        assertEquals("nothing is left ticking", 0, scheduler.pending)
    }

    @Test fun `the fade never goes below 0 or above 1 and ends exactly on 0 before the pause`() {
        timer.setMinutes(1)
        at(60_000)
        assertTrue("fades out of range: $fades", fades.all { it in 0f..1f })
        // Monotonic non-increasing across the whole run, up to the restore.
        val beforeRestore = fades.dropLast(1)
        assertEquals(beforeRestore, beforeRestore.sortedDescending())
    }

    @Test fun `a timer that ends while the user has paused does not pause again, and still restores`() {
        timer.setMinutes(1)
        at(40_000)
        playback.isPlaying = false
        at(60_000)
        assertEquals(0, playback.pauses)
        assertEquals(1f, lastFade, 0f)
        assertEquals(SleepTimerState.Off, timer.state)
    }

    @Test fun `extend during the fade adds ten minutes and brings the volume straight back`() {
        timer.setMinutes(15)
        at(14 * 60_000L + 45_000)
        assertEquals(0.5f, lastFade, 1e-6f)
        timer.extend()
        assertEquals(SleepTimerState.Timed(start + 25 * 60_000L), timer.state)
        assertEquals(1f, lastFade, 0f)
        at(15 * 60_000L)
        assertEquals("the old end is no longer an end", 0, playback.pauses)
        at(25 * 60_000L)
        assertEquals(1, playback.pauses)
    }

    @Test fun `cancel restores the volume without pausing`() {
        timer.setMinutes(1)
        at(45_000)
        assertEquals(0.5f, lastFade, 1e-6f)
        timer.cancel()
        assertEquals(1f, lastFade, 0f)
        assertEquals(0, playback.pauses)
        assertEquals(SleepTimerState.Off, timer.state)
        at(120_000)
        assertEquals(0, playback.pauses)
        assertEquals(0, scheduler.pending)
    }

    @Test fun `a new timer replaces the running one`() {
        timer.setMinutes(60)
        timer.setMinutes(15)
        at(15 * 60_000L)
        assertEquals(1, playback.pauses)
        at(60 * 60_000L)
        assertEquals(1, playback.pauses)
    }

    @Test fun `custom lengths are 1 to 180 minutes`() {
        timer.setMinutes(1)
        timer.setMinutes(180)
        assertEquals(SleepTimerState.Timed(start + 180 * 60_000L), timer.state)
        assertThrows(IllegalArgumentException::class.java) { timer.setMinutes(0) }
        assertThrows(IllegalArgumentException::class.java) { timer.setMinutes(181) }
    }

    @Test fun `the published minutes round up and change once a minute`() {
        timer.setMinutes(2)
        assertEquals(SleepTimerStatus(SleepTimerState.Timed(start + 120_000), 2), updates.last())
        at(1_000)
        assertEquals("119 s left still reads 2 min", 2, updates.last().remainingMinutes)
        at(60_000)
        assertEquals(1, updates.last().remainingMinutes)
        at(120_000)
        assertEquals(SleepTimerStatus(SleepTimerState.Off, null), updates.last())
        assertEquals(
            "one update per change, not one per tick",
            listOf(2, 1, null),
            updates.map { it.remainingMinutes },
        )
    }

    // ---- end of this track ----

    @Test fun `end of track fades over the track's last 30 s and lets the player pause at its end`() {
        playback.durationMs = 200_000
        playback.positionMs = 100_000
        timer.setEndOfTrack()
        assertEquals(true, playback.pauseAtEnd)
        assertEquals(1f, lastFade, 0f)
        playback.positionMs = 170_000
        at(1_000)
        assertEquals("t=0 s of the fade", 1f, lastFade, 1e-6f)
        playback.positionMs = 185_000
        at(2_000)
        assertEquals("t=15 s of the fade", 0.5f, lastFade, 1e-6f)
        playback.positionMs = 200_000
        at(3_000)
        assertEquals("t=30 s of the fade", 0f, lastFade, 0f)
        // The player pauses itself at the boundary (pauseAtEndOfMediaItems) and says so.
        playback.isPlaying = false
        timer.onPausedAtEndOfItem()
        assertEquals(0, playback.pauses)
        assertEquals(false, playback.pauseAtEnd)
        assertEquals(1f, lastFade, 0f)
        assertEquals(SleepTimerState.Off, timer.state)
    }

    @Test fun `a track shorter than 30 s fades over its whole length`() {
        playback.durationMs = 20_000
        playback.positionMs = 10_000
        timer.setEndOfTrack()
        assertEquals(0.5f, lastFade, 1e-6f)
    }

    @Test fun `an unknown duration does not fade`() {
        playback.durationMs = 0
        timer.setEndOfTrack()
        assertEquals(1f, lastFade, 0f)
    }

    @Test fun `extend on end of track becomes a timer ten minutes past the track's end`() {
        playback.durationMs = 200_000
        playback.positionMs = 150_000
        timer.setEndOfTrack()
        timer.extend()
        assertEquals(SleepTimerState.Timed(start + 50_000 + 10 * 60_000L), timer.state)
        assertEquals(false, playback.pauseAtEnd)
    }

    @Test fun `the queue ending ends an end-of-track timer but not a timed one`() {
        playback.durationMs = 200_000
        timer.setEndOfTrack()
        timer.onPlaybackEnded()
        assertEquals(SleepTimerState.Off, timer.state)

        timer.setMinutes(30)
        timer.onPlaybackEnded()
        timer.onPausedAtEndOfItem()
        assertEquals(SleepTimerState.Timed(start + 30 * 60_000L), timer.state)
    }
}
