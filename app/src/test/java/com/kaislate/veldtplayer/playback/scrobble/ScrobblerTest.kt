// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.scrobble

import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicError
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.playback.TrackRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * [Scrobbler] (design spec §3, §5; plan Review Focus 1, 2, 5; N3 review fix round 1). Plain JUnit
 * — [Scrobbler] itself touches no Android/Media3 type (see its class KDoc): [sender], [flush] and
 * [enqueueFlush] are plain fakes, [ScrobbleQueue] is the real, temp-dir-backed class (it is
 * Android-free too — see `ScrobbleQueueTest`), and [Scheduler] is [FakeScheduler] below, which
 * lets a test fire a "played" timer on command instead of sleeping.
 *
 * [Dispatchers.Unconfined] backs every [Harness]'s scope rather than a `TestScope`/
 * `runTest`: nothing [Scrobbler] launches ever genuinely suspends (every fake here returns
 * synchronously), so `Unconfined` runs each launched coroutine to completion inline, and a plain
 * `@Test fun` can assert on [Harness.senderCalls] etc. immediately after calling into [Scrobbler]
 * with no `advanceUntilIdle()` needed. (Production uses `Dispatchers.IO` instead — see
 * `PlaybackService`'s wiring — which changes nothing about [Scrobbler]'s own contract, only where
 * the launched work happens to run.)
 *
 * **Review fix round 1, item 1 (CRITICAL) — what changed:** every call to
 * [Scrobbler.onMediaItemTransition] here now passes an explicit `isPlaying`. Tests that model a
 * genuinely NEW play-through starting from paused/buffering use `isPlaying = false` followed by a
 * real [Scrobbler.onIsPlayingChanged]`(true)`; tests that model an auto-advance, a skip while
 * playing, or a repeat-one wrap — where Media3 fires ONLY `onMediaItemTransition`, with no
 * following play event, because the playing state never changes — use `isPlaying = true` at the
 * transition itself and call `onIsPlayingChanged` NOT AT ALL for that step. The previous version
 * of this file called `onIsPlayingChanged(true)` after every transition unconditionally, including
 * ones meant to model continuous playback — an event the real player never sends there — which is
 * exactly how the missing-scrobble bug this fix closes stayed hidden.
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals(listOf("acct-1"), h.flushCalls)
        } finally {
            h.close()
        }
    }

    // ---------------------------------------------------------------- auto-advance / repeat-one (item 1)

    /**
     * Review fix round 1, item 1 (CRITICAL). Gapless auto-advance into a brand new item: the
     * player was already playing, so Media3 fires ONLY `onMediaItemTransition` — no
     * `onIsPlayingChanged` follows, because the playing state never changed. Before the fix,
     * nothing here would ever send now-playing/played until the user next paused.
     *
     * **This file's control** (see the task report): reverting the fix — making
     * `onMediaItemTransition` ignore `isPlaying` and always wait for a later
     * `onIsPlayingChanged(true)` — reddens this test (0 sends instead of 2).
     */
    @Test fun `an auto-advance transition while already playing, with no play event, sends now-playing and played`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
            assertEquals("now-playing must fire immediately, with no separate play event", 1, h.senderCalls.size)

            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals("and played fires at the threshold", 2, h.senderCalls.size)
            assertEquals(listOf(Triple(track, false, null), Triple(track, true, 0L)), h.senderCalls)
        } finally {
            h.close()
        }
    }

    /** Same fix, for a transition AWAY from an item mid-play-through: the old item's timer is
     *  still cancelled (nothing sent for it), and the new item gets its now-playing immediately —
     *  no `onIsPlayingChanged` call happens for either half of this transition. */
    @Test fun `an auto-advance transition (already playing) cancels the old item's timer and starts the new one immediately`() {
        val h = Harness()
        try {
            val other = TrackRef("acct-1", "song-2")
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L
            // Auto-advance / skip WHILE PLAYING: only onMediaItemTransition fires here.
            h.scrobbler.onMediaItemTransition(other, 240_000L, isPlaying = true)

            h.scheduler.fireAll() // the OLD item's cancelled timer must not run
            assertEquals(
                "track's now-playing, then other's now-playing immediately at transition — no played for either yet",
                listOf(Triple(track, false, null), Triple(other, false, null)),
                h.senderCalls,
            )
        } finally {
            h.close()
        }
    }

    /** Plan Review Focus 2, closed together with item 1's fix: repeat-one while STILL PLAYING —
     *  Media3 reports the wrap as `onMediaItemTransition(reason = REPEAT)` with no following
     *  `onIsPlayingChanged`, since the playing state never changes across the wrap. */
    @Test fun `a repeat-one wrap while still playing starts a second play-through with no separate play event`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals(2, h.senderCalls.size) // now-playing + played, first play-through

            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true) // the wrap; same TrackRef
            assertEquals("now-playing must fire again immediately, with no separate play event", 3, h.senderCalls.size)
            h.now = 240_000L
            h.scheduler.fireAll()
            assertEquals("and played again", 4, h.senderCalls.size)
            assertEquals(listOf(false, true, false, true), h.senderCalls.map { it.second })
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false) // threshold 120_000
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false) // threshold 120_000
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L
            h.scrobbler.onIsPlayingChanged(false) // paused well short of the threshold

            h.scheduler.fireAll() // the cancelled timer must not run
            assertEquals("only the now-playing send", 1, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    // -------------------------------------------------------------------------------- eligibility

    @Test fun `a local item (no TrackRef) sends nothing at all`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(null, 240_000L, isPlaying = true)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
            h.scrobbler.onMediaItemTransition(track, 0L, isPlaying = false) // unknown at transition time
            h.scrobbler.onIsPlayingChanged(true)
            assertEquals("unknown duration defaults to the 240s cap", 240_000L, h.scheduler.latestPendingDelay())

            h.scrobbler.onDurationKnown(track, 90_000L) // a 90s track: real threshold 45_000
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
            h.scrobbler.onMediaItemTransition(track, 0L, isPlaying = false) // unknown -> 240s cap
            h.scrobbler.onIsPlayingChanged(true)
            h.now = 50_000L // 50s already listened while buffering
            h.scrobbler.onDurationKnown(track, 60_000L) // a 60s track: real threshold 30_000, already passed

            assertEquals("must send immediately, not wait for a timer", 2, h.senderCalls.size)
        } finally {
            h.close()
        }
    }

    @Test fun `onDurationKnown is a no-op with nothing eligible playing`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(null, 0L, isPlaying = true) // local/ineligible
            h.scrobbler.onDurationKnown(null, 90_000L) // must not throw or schedule anything
            assertEquals(null, h.scheduler.latestPendingDelay())
        } finally {
            h.close()
        }
    }

    // ------------------------------------------------------------------------------------- release

    @Test fun `release cancels a pending timer so nothing fires after it`() {
        val h = Harness()
        try {
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false)
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
     * performed while paused looks like from here) still does not get counted. This test models a
     * GENUINE pause→resume (a real `onIsPlayingChanged(false)` then `(true)`), unlike the
     * auto-advance tests above.
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
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = false) // threshold 120_000
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

    // -------------------------------------------------------------------- write-ahead (item 4)

    @Test fun `write-ahead - an ok result removes the played entry that was queued before the send`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
            h.now = 120_000L
            h.scheduler.fireAll()
            assertEquals(emptyList<QueuedScrobble>(), h.queue.forSource("acct-1"))
        } finally {
            h.close()
        }
    }

    /**
     * Review fix round 1, item 4 (controller ruling). Models "the process died mid-request" as
     * closely as a plain-JUnit test can: a `sender` that genuinely suspends on a
     * [CompletableDeferred] the test controls, fired via [FakeScheduler], then the SCOPE's job is
     * cancelled out from under it — standing in for a teardown cancellation. The entry must
     * already be queued the instant the timer fires (write-ahead, synchronous, before [sender] is
     * even called), and must still be there after the cancellation, since neither the `Ok` nor the
     * `Rejected` removal branch ever got to run.
     */
    @Test fun `write-ahead - the entry is queued before the send, and a mid-request cancellation leaves it queued`() {
        val queueDir = Files.createTempDirectory("scrobbler-write-ahead-test").toFile()
        try {
            val queue = ScrobbleQueue(queueDir)
            val scheduler = FakeScheduler()
            val job = SupervisorJob()
            val scope = CoroutineScope(job + Dispatchers.Unconfined)
            val gate = CompletableDeferred<ScrobbleResult>()
            var now = 0L
            val scrobbler = Scrobbler(
                scope = scope,
                clock = ListenClock(now = { now }, wallClock = { 0L }),
                sourceExists = { true },
                sender = { _, _, _ -> gate.await() }, // suspends "mid-request" until told otherwise
                queue = queue,
                flush = {},
                enqueueFlush = {},
                scheduler = scheduler,
            )

            scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
            now = 120_000L
            scheduler.fireAll() // fires trySendPlayed: queues the entry, THEN suspends on the gate

            assertEquals(
                "the entry must already be queued before the send resolves (write-ahead)",
                listOf(QueuedScrobble("acct-1", "song-1", 0L)),
                queue.forSource("acct-1"),
            )

            job.cancel() // simulate teardown / process death mid-request

            assertEquals(
                "a cancelled mid-request send must leave the entry queued",
                listOf(QueuedScrobble("acct-1", "song-1", 0L)),
                queue.forSource("acct-1"),
            )
        } finally {
            queueDir.deleteRecursively()
        }
    }

    /**
     * Fix round 2, finding 2 (double delivery) — the PRODUCER side: [Scrobbler] itself must mark
     * the write-ahead entry in-flight for exactly the span its live send is outstanding, so a
     * concurrent [com.kaislate.veldtplayer.data.scrobble.ScrobbleFlusher.flush] (tested on the
     * CONSUMER side in `ScrobbleFlusherTest`) has something to skip. Reuses the same
     * suspend-on-a-gate shape as the write-ahead test above.
     */
    @Test fun `the write-ahead entry is marked in-flight for exactly the span the live send is outstanding`() {
        val queueDir = Files.createTempDirectory("scrobbler-in-flight-test").toFile()
        try {
            val queue = ScrobbleQueue(queueDir)
            val scheduler = FakeScheduler()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val gate = CompletableDeferred<ScrobbleResult>()
            var now = 0L
            val entry = QueuedScrobble("acct-1", "song-1", 0L)
            val scrobbler = Scrobbler(
                scope = scope,
                clock = ListenClock(now = { now }, wallClock = { 0L }),
                sourceExists = { true },
                sender = { _, _, _ -> gate.await() },
                queue = queue,
                flush = {},
                enqueueFlush = {},
                scheduler = scheduler,
            )

            scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
            now = 120_000L
            scheduler.fireAll() // queues the entry, marks it in-flight, then suspends on the gate

            assertTrue("must be marked in-flight while the live send is outstanding", queue.isInFlight(entry))

            gate.complete(ScrobbleResult.Ok) // the send resolves

            assertFalse("must be cleared once the live send has resolved", queue.isInFlight(entry))
        } finally {
            queueDir.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- duration identity (fix round 2, item 1)

    /**
     * Fix round 2, finding 1 (IMPORTANT — new). Media3 delivers `onTimelineChanged` for a queue
     * replacement BEFORE `onMediaItemTransition`, and by then `player.currentMediaItem` already
     * reports the NEXT item — so a duration correction for track B can arrive while [Scrobbler]
     * still considers track A current. The exact scenario from the review: A is 300s with 100s
     * already listened (A's own threshold, 150_000, not yet crossed); B is an album's first track
     * at 180s (B's threshold would be 90_000). Applying B's duration to A's clock would make A's
     * 100_000 ms listened look like it had crossed a 90_000 ms threshold — a false "played" for a
     * track the user never actually finished.
     *
     * **This file's control** (see the task report): removing the `track != current` identity
     * check in `Scrobbler.onDurationKnown` reddens this test — A gets falsely scrobbled as played.
     */
    @Test fun `a duration correction for a different item (queue replaced) is ignored, not applied to the current play-through`() {
        val h = Harness()
        try {
            h.senderResult = ScrobbleResult.Ok
            h.scrobbler.onMediaItemTransition(track, 300_000L, isPlaying = true) // A: threshold 150_000
            h.now = 100_000L // 100s of A listened; A's own threshold not yet crossed
            assertEquals("just A's now-playing so far", 1, h.senderCalls.size)

            val trackB = TrackRef("acct-1", "song-2")
            // Simulates onTimelineChanged firing for B BEFORE onMediaItemTransition — B's duration
            // arrives while `currentTrack` (Scrobbler's own state) is still A.
            h.scrobbler.onDurationKnown(trackB, 180_000L) // B's threshold would be 90_000 if wrongly applied to A

            assertEquals(
                "A must not have been falsely scrobbled as played by B's duration",
                1,
                h.senderCalls.size,
            )
            h.scheduler.fireAll() // if a played timer had been (wrongly) rescheduled to fire now, this would send it
            assertEquals("still just A's now-playing — nothing falsely sent for A", 1, h.senderCalls.size)

            // The REAL transition to B, when it follows, starts B's own play-through correctly.
            h.scrobbler.onMediaItemTransition(trackB, 180_000L, isPlaying = true)
            assertEquals("B's own now-playing", 2, h.senderCalls.size)
            h.now = 190_000L // 90s into B — B's own threshold
            h.scheduler.fireAll()
            assertEquals("B's own played, at B's own threshold", 3, h.senderCalls.size)
            assertEquals(listOf(false, false, true), h.senderCalls.map { it.second })
        } finally {
            h.close()
        }
    }

    // --------------------------------------------------------------- auth-blocked source (item 6)

    /** Review fix round 1, item 6 (controller ruling). */
    @Test fun `an auth-blocked source skips the live now-playing send entirely`() {
        val h = Harness()
        try {
            h.queue.setAuthBlocked("acct-1", true)
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true)
            assertEquals(
                "no now-playing request while auth-blocked",
                emptyList<Triple<TrackRef, Boolean, Long?>>(),
                h.senderCalls,
            )
        } finally {
            h.close()
        }
    }

    /** Review fix round 1, item 6 (controller ruling): the write-ahead entry alone is the
     *  outcome — no request is ever attempted while the source is auth-blocked. */
    @Test fun `an auth-blocked source routes a played scrobble straight to the queue, with no request`() {
        val h = Harness()
        try {
            h.queue.setAuthBlocked("acct-1", true)
            h.wall = 42L
            h.scrobbler.onMediaItemTransition(track, 240_000L, isPlaying = true) // now-playing skipped too
            h.now = 120_000L
            h.scheduler.fireAll()

            assertEquals(
                "no request at all while auth-blocked",
                emptyList<Triple<TrackRef, Boolean, Long?>>(),
                h.senderCalls,
            )
            assertEquals(listOf(QueuedScrobble("acct-1", "song-1", 42L)), h.queue.forSource("acct-1"))
        } finally {
            h.close()
        }
    }
}
