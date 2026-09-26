// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.scrobble

import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicError
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.playback.TrackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [Scrobbler] (design spec §3, §5; plan Review Focus 1, 2, 5). Plain JUnit — [Scrobbler] itself
 * touches no Android/Media3 type (see its class KDoc): [sender], [flush] and [enqueueFlush] are
 * plain fakes, [ScrobbleQueue] is the real, temp-dir-backed class (it is Android-free too — see
 * `ScrobbleQueueTest`), and [Scheduler] is [FakeScheduler] below, which lets a test fire a
 * "played" timer on command instead of sleeping.
 *
 * [Dispatchers.Unconfined] backs every [Harness]'s scope rather than a `TestScope`/
 * `runTest`: nothing [Scrobbler] launches ever genuinely suspends (every fake here returns
 * synchronously), so `Unconfined` runs each launched coroutine to completion inline, and a plain
 * `@Test fun` can assert on [Harness.senderCalls] etc. immediately after calling into [Scrobbler]
 * with no `advanceUntilIdle()` needed.
 */
class ScrobblerTest {

    private val track = TrackRef("acct-1", "song-1")

    /** Records every [Scheduler.postDelayed] call; [fireAll] runs whichever are still pending
     *  (not cancelled) — standing in for "the real delay has now elapsed". */
    private class FakeScheduler : Scheduler {
        private class Pending(val delayMs: Long, val action: () -> Unit) {
            var cancelled = false
        }

        private val pending = mutableListOf<Pending>()

        override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
            val entry = Pending(delayMs, action)
            pending += entry
            return { entry.cancelled = true }
        }

        fun fireAll() {
            val toFire = pending.filterNot { it.cancelled }
            pending.clear()
            toFire.forEach { it.action() }
        }

        /** The delay of the most recently scheduled, still-pending task — what a test checks to
         *  confirm a (re)schedule happened, and with which delay. */
        fun latestPendingDelay(): Long? = pending.lastOrNull { !it.cancelled }?.delayMs
    }

    /** One [Scrobbler] wired to fakes, plus everything a test needs to inspect or drive. */
    private class Harness {
        var now = 0L
        var wall = 0L
        val queueDir = Files.createTempDirectory("scrobbler-test").toFile()
        val queue = ScrobbleQueue(queueDir)
        val scheduler = FakeScheduler()

        var sourceExistsResult = true
        var senderResult: ScrobbleResult = ScrobbleResult.Ok
        val senderCalls = mutableListOf<Triple<TrackRef, Boolean, Long?>>()
        val flushCalls = mutableListOf<String>()
        var enqueueFlushCalls = 0

        val scrobbler = Scrobbler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = ListenClock(now = { now }, wallClock = { wall }),
            sourceExists = { sourceExistsResult },
            sender = { trackRef, submission, timeMs ->
                senderCalls += Triple(trackRef, submission, timeMs)
                senderResult
            },
            queue = queue,
            flush = { sourceId -> flushCalls += sourceId },
            enqueueFlush = { enqueueFlushCalls++ },
            scheduler = scheduler,
        )

        fun close() = queueDir.deleteRecursively()
    }

    // -------------------------------------------------------------------------------- now playing

    @Test fun `now playing sends once per play-through, not again on a mere resume`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals(listOf(Triple(track, false, null)), h.senderCalls)

            h.scrobbler.onIsPlayingChanged(false)
            h.scrobbler.onIsPlayingChanged(true) // resume: must NOT re-send now-playing
            assertEquals(listOf(Triple(track, false, null)), h.senderCalls)
        } finally {
            h.close()
        }
    }

    @Test fun `a successful now-playing send triggers a piggyback flush of its source`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals(listOf("acct-1"), h.flushCalls)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------------------ played

    @Test fun `played sends exactly once, at the threshold, with the wall time start captured`() {
        val h = Harness()
        try {
            h.wall = 1_700_000_000_000L
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L) // threshold 120_000
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals(120_000L, h.scheduler.latestPendingDelay())

            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals(
                listOf(Triple(track, false, null), Triple(track, true, 1_700_000_000_000L)),
                h.senderCalls,
            )

            h.scheduler.fireAll() // nothing left pending; must not send a third time
            assertEquals(2, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    @Test fun `a successful played send also triggers a piggyback flush`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.flushCalls.clear() // drop the now-playing flush; isolate the played one
            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals(listOf("acct-1"), h.flushCalls)
        } finally {
            h.close()
        }
    }

    @Test fun `pause before the threshold cancels the timer, so nothing is ever sent for it`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 240_000L) // threshold 120_000
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L
            h.scrobbler.onIsPlayingChanged(false) // paused well short of the threshold

            h.scheduler.fireAll() // the cancelled timer must not run
            assertEquals("only the now-playing send", 1, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    @Test fun `a transition before the threshold cancels the old item's timer`() {
        val h = Harness()
        try {
            val other = TrackRef("acct-1", "song-2")
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L
            h.scrobbler.onMediaItemTransition(other, 240_000L) // transition away, short of threshold

            h.scheduler.fireAll() // the old item's cancelled timer must not run
            assertEquals("only track's now-playing send", listOf(Triple(track, false, null)), h.senderCalls)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------------ repeat-one

    /** Plan Review Focus 2. Media3 reports repeat-one as a genuine `onMediaItemTransition` call
     *  for the SAME item; [Scrobbler] does not special-case it — every transition is a fresh
     *  play-through unconditionally. */
    @Test fun `a repeated transition to the same track starts a second play-through`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals(2, h.senderCalls.size) // now-playing + played, first play-through

            h.scrobbler.onMediaItemTransition(track, 240_000L) // "repeat" — same TrackRef again
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals("now-playing must fire again for the new play-through", 3, h.senderCalls.size)
            h.now = 240_000L
            h.scheduler.fireAll()
            assertEquals("and played again", 4, h.senderCalls.size)
            assertEquals(listOf(false, true, false, true), h.senderCalls.map { it.second })
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- eligibility

    @Test fun `a local item (no TrackRef) sends nothing at all`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(null, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 999_999L
            h.scheduler.fireAll()
            assertEquals(emptyList<Triple<TrackRef, Boolean, Long?>>(), h.senderCalls)
        } finally {
            h.close()
        }
    }

    @Test fun `a track whose source no longer exists sends nothing, same as a local item`() {
        val h = Harness()
        try {
            h.sourceExistsResult = false
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 999_999L
            h.scheduler.fireAll()
            assertEquals(emptyList<Triple<TrackRef, Boolean, Long?>>(), h.senderCalls)
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------- failure handling

    @Test fun `unreachable queues the entry with the original start time and enqueues a flush`() {
        val h = Harness()
        try {
            h.wall = 1_234L
            h.senderResult = ScrobbleResult.Unreachable
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 120_000L
            h.scheduler.fireAll()

            assertEquals(listOf(QueuedScrobble("acct-1", "song-1", 1_234L)), h.queue.forSource("acct-1"))
            assertEquals(1, h.enqueueFlushCalls)
            assertFalse(h.queue.isAuthBlocked("acct-1"))
        } finally {
            h.close()
        }
    }

    @Test fun `a credential rejection (40) queues the entry, auth-blocks the source, and enqueues nothing`() {
        val h = Harness()
        try {
            h.wall = 5_678L
            h.senderResult = ScrobbleResult.Rejected(SubsonicError.WRONG_CREDENTIALS, 40)
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 120_000L
            h.scheduler.fireAll()

            assertEquals(listOf(QueuedScrobble("acct-1", "song-1", 5_678L)), h.queue.forSource("acct-1"))
            assertTrue(h.queue.isAuthBlocked("acct-1"))
            assertEquals("a credential rejection must never enqueue the retry job", 0, h.enqueueFlushCalls)
        } finally {
            h.close()
        }
    }

    @Test fun `a non-credential rejection (70) is dropped - not queued, not blocked, not enqueued`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Rejected(SubsonicError.NOT_FOUND, 70)
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 120_000L
            h.scheduler.fireAll()

            assertEquals(emptyList<QueuedScrobble>(), h.queue.forSource("acct-1"))
            assertFalse(h.queue.isAuthBlocked("acct-1"))
            assertEquals(0, h.enqueueFlushCalls)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------- duration arrives late

    @Test fun `a duration learned after transition reschedules the timer to the real threshold`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 0L) // unknown at transition time
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals("unknown duration defaults to the 240s cap", 240_000L, h.scheduler.latestPendingDelay())

            h.scrobbler.onDurationKnown(90_000L) // a 90s track: real threshold 45_000
            assertEquals(45_000L, h.scheduler.latestPendingDelay())

            h.now = 45_000L
            h.scheduler.fireAll()
            assertEquals(2, h.senderCalls.size) // now-playing + played, at the REAL threshold
        } finally {
            h.close()
        }
    }

    @Test fun `a duration learned after already listening past the real threshold sends immediately`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 0L) // unknown -> 240s cap
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L // 50s already listened while buffering
            h.scrobbler.onDurationKnown(60_000L) // a 60s track: real threshold 30_000, already passed

            assertEquals("must send immediately, not wait for a timer", 2, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    @Test fun `onDurationKnown is a no-op with nothing eligible playing`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(null, 0L) // local/ineligible
            h.scrobbler.onDurationKnown(90_000L) // must not throw or schedule anything
            assertEquals(null, h.scheduler.latestPendingDelay())
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------------------- release

    @Test fun `release cancels a pending timer so nothing fires after it`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 240_000L)
            h.scrobbler.onIsPlayingChanged(true)
            h.scrobbler.release()

            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals("only the now-playing send; the released timer must not fire", 1, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------------- seeks

    /**
     * Plan Review Focus 1. There is no seek-reporting method on [Scrobbler] at all — the facade
     * never forwards `onPositionDiscontinuity` — so this proves the only way a seek COULD leak in
     * (a gap in real elapsed time between a pause and the next resume, exactly what a seek
     * performed while paused looks like from here) still does not get counted.
     *
     * **This file's control** (see the task report): temporarily removing the `clock.onPaused()`
     * call from `Scrobbler.onIsPlayingChanged`'s paused branch reddens this test — without it, the
     * clock's internal "since" anchor is never cleared, so the very next `onPlaying()` is a no-op
     * (idempotent-by-design) and the huge gap below gets folded in as if it had all been real
     * listening, crossing the threshold immediately on resume instead of staying at 20s listened.
     */
    @Test fun `a large gap while paused, as a seek performed then would look, is never counted as listened`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 240_000L) // threshold 120_000
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 10_000L // 10s of real listening
            h.scrobbler.onIsPlayingChanged(false) // paused; the user seeks to 90% here — no event at all
            h.now = 900_000L // a huge gap passes while paused/seeking; must not count
            h.scrobbler.onIsPlayingChanged(true) // resume
            h.now = 910_000L // another 10s of real listening

            h.scheduler.fireAll()
            assertEquals(
                "only 20s of real listening happened; 120000 must not have been crossed",
                1,
                h.senderCalls.size,
            )
        } finally {
            h.close()
        }
    }
}
