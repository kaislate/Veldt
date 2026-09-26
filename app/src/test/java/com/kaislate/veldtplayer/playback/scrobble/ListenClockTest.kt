// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.scrobble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ListenClock] (design spec §3, plan Review Focus 1). Plain JUnit: the class touches no
 * Android/Media3 type (see its KDoc), so it needs no Robolectric.
 *
 * [now] and [wall] are fakes the tests move by hand, standing in for `SystemClock
 * .elapsedRealtime` and `System.currentTimeMillis` respectively — two independently movable
 * clocks, which is the point: [now] drives the listened accumulator, [wall] only ever backs
 * [ListenClock.startedAtWallMs].
 */
class ListenClockTest {

    private var now = 0L
    private var wall = 0L
    private val clock = ListenClock(now = { now }, wallClock = { wall })

    /** Media3's `C.TIME_UNSET`, pinned by the task brief as `Long.MIN_VALUE + 1` — mirrored here
     *  rather than importing `androidx.media3.common.C`, which is exactly what keeps this file a
     *  plain JVM test. */
    private val timeUnset = Long.MIN_VALUE + 1

    /**
     * TOTAL table: every duration this suite names, and the threshold it must produce, in one
     * assertion, so a regression names the exact duration that moved. Covers: a 4-min track, a
     * 10-min track (the 240 000 cap), the 30s floor from both sides, a 25s track (never), and
     * every shape "unknown" can take (`0`, negative, and Media3's `C.TIME_UNSET`).
     */
    @Test fun `threshold is min(duration slash 2, 240000), null under 30s, 240000 when unknown`() {
        val table = listOf(
            240_000L to 120_000L, // 4-min track
            600_000L to 240_000L, // 10-min track: the 240 000 cap
            30_000L to 15_000L, // exactly the floor: still a real threshold
            29_999L to null, // one ms under the floor: never
            25_000L to null, // 25-s track (brief's own example)
            0L to 240_000L, // unknown: zero
            -1L to 240_000L, // unknown: negative
            timeUnset to 240_000L, // unknown: Media3's C.TIME_UNSET
        )
        assertEquals(
            table,
            table.map { (duration, _) -> duration to clock.apply { start(duration) }.thresholdMs() },
        )
    }

    /**
     * The core of the clock, and this file's control (see the task report): played 1:59 of a
     * 4-minute track does not cross; one more second crosses at EXACTLY the threshold (120 000);
     * a pause in between — even while the wall clock races far ahead during it — folds into the
     * accumulator and does not reset it. Dropping the fold in `onPaused` (control: comment it out
     * so the paused span leaks into the next playing span) or reading `now` instead of gating on
     * `playingSinceMs` both send this test red.
     */
    @Test fun `pause folds the elapsed span without resetting it, and the threshold crosses exactly at 120000`() {
        clock.start(240_000L) // 4-min track, threshold 120 000
        now = 0L
        clock.onPlaying()

        now = 119_000L // played 1:59
        assertEquals(119_000L, clock.listenedMs())
        assertFalse("1:59 of a 4-min track must not have crossed yet", clock.crossed())
        assertEquals(1_000L, clock.remainingToThresholdMs())

        clock.onPaused()
        now = 900_000L // wall time races ahead while paused; must not count
        assertEquals("a paused span leaked into the accumulator", 119_000L, clock.listenedMs())
        assertFalse(clock.crossed())

        clock.onPlaying() // resume
        now = 901_000L // one more second of ACTUAL playing
        assertEquals(120_000L, clock.listenedMs())
        assertTrue("120 000 ms listened against a 120 000 threshold must have crossed", clock.crossed())
        assertNull("already crossed: remaining must be null, not zero or negative", clock.remainingToThresholdMs())
    }

    @Test fun `onPlaying is idempotent — a second call while already playing does not re-anchor`() {
        clock.start(240_000L)
        now = 0L
        clock.onPlaying()
        now = 50_000L
        clock.onPlaying() // spurious duplicate event; must not move the anchor to here
        now = 80_000L
        assertEquals(80_000L, clock.listenedMs())
    }

    @Test fun `onPaused with nothing playing is a no-op`() {
        clock.start(240_000L)
        now = 500L
        clock.onPaused() // never played; must not throw or go negative
        assertEquals(0L, clock.listenedMs())
    }

    @Test fun `start resets the accumulator and sent for a new play-through, and re-anchors the wall clock`() {
        clock.start(240_000L)
        now = 0L
        clock.onPlaying()
        now = 120_000L
        assertTrue(clock.crossed())
        clock.markSent()
        assertTrue(clock.sent)

        wall = 1_700_000_000_000L
        clock.start(240_000L) // a new play-through: media-item transition, or a repeat-one wrap
        assertEquals("listened must reset on a new play-through", 0L, clock.listenedMs())
        assertFalse("sent must reset on a new play-through", clock.sent)
        assertFalse(clock.crossed())
        assertEquals(1_700_000_000_000L, clock.startedAtWallMs)
    }

    @Test fun `startedAtWallMs comes from the wall clock, never from the elapsed clock`() {
        now = 987_654L
        wall = 1_700_000_000_000L
        clock.start(240_000L)
        assertEquals(1_700_000_000_000L, clock.startedAtWallMs)
    }

    @Test fun `remainingToThresholdMs while playing is threshold minus listened`() {
        clock.start(240_000L) // threshold 120 000
        now = 1_000L
        clock.onPlaying()
        now = 21_000L // 20 000 ms listened
        assertEquals(120_000L - 20_000L, clock.remainingToThresholdMs())
    }

    @Test fun `remainingToThresholdMs is null for a track that will never cross`() {
        clock.start(25_000L) // under the 30s floor
        now = 0L
        clock.onPlaying()
        now = 25_000L
        assertNull(clock.remainingToThresholdMs())
        assertFalse(clock.crossed())
    }

    @Test fun `markSent is observable via sent and does not affect crossed or listenedMs`() {
        clock.start(240_000L)
        assertFalse(clock.sent)
        clock.markSent()
        assertTrue(clock.sent)
        assertEquals(0L, clock.listenedMs())
        assertFalse(clock.crossed())
    }
}
