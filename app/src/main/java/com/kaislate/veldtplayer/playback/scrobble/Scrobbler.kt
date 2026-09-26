// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.scrobble

import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.playback.TrackRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A single delayed callback. Exists so [Scrobbler] never touches `android.os.Handler` directly
 * (design spec §6: the listening clock — and the class that drives it — stay testable without a
 * real looper) and so a test controls time explicitly rather than sleeping. Mirrors
 * [com.kaislate.veldtplayer.playback.NetworkReturn.listen]'s own shape: the caller gets back a
 * plain cancel lambda instead of a token type, which is this codebase's existing idiom for "here
 * is a thing you can undo".
 */
interface Scheduler {
    /** Runs [action] after [delayMs]. The returned lambda cancels it; calling it after [action]
     *  has already run, or calling it twice, is safe and a no-op. */
    fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit
}

/**
 * Drives one [ListenClock] from real player events and sends the two scrobbles the design spec
 * describes (§3, §5) for server tracks only — a local `MediaItem`'s uri never parses to a
 * [TrackRef] at all (`VeldtUri.parse`, done by the caller before this class ever sees it — see
 * [onMediaItemTransition]), so "zero sends for a local item" falls out of never being told about
 * one, not out of a check against the local source's id (plan Global Constraint 3).
 *
 * This class is deliberately pure — no `Player.Listener`, no `Handler`, no coroutine dispatcher
 * choice baked in, and no dependency on the CONCRETE `ScrobbleFlusher`/`SubsonicClient` (both take
 * [flush] and [sender] instead, as plain functions) — so [ScrobblerTest] drives it directly with
 * plain calls, a fake [Scheduler] and fake functions, with no Room/OkHttp/Robolectric anywhere.
 * The real `Player.Listener` (`playback/PlaybackService.kt`'s `ScrobblerPlayerListener`) is the
 * thin, untested-by-unit-test facade the task brief asks for: it extracts a [TrackRef]? via
 * `VeldtUri.parse` and a duration from the real Media3 `Player` and calls straight through to the
 * three methods below.
 *
 * ## Now playing
 * The first [onIsPlayingChanged]`(true)` of a play-through sends `submission=false`,
 * fire-and-forget: the result is ignored except that [ScrobbleResult.Ok] triggers a piggyback
 * [flush] (design spec §5). Never queued on failure — design spec §3.
 *
 * ## Played
 * Scheduled via [scheduler] for [ListenClock.remainingToThresholdMs] every time playing starts (a
 * fresh remaining value each time, since it is computed from [ListenClock.listenedMs] at that
 * moment) and cancelled on pause or on the next transition — this is what makes "pause before
 * threshold ⇒ nothing sent" and "transition before threshold ⇒ nothing sent for the old item"
 * true: the callback that would have sent it never fires. [ListenClock.markSent] is called
 * synchronously, before the network call, so nothing can send it twice — recovery from a failed
 * send is the queue's job (a real [com.kaislate.veldtplayer.data.scrobble.ScrobbleFlusher] in
 * production), never a second attempt from here.
 *
 * ## Duration arriving late
 * `onMediaItemTransition` commonly fires before Media3 knows the item's duration
 * (`C.TIME_UNSET`). [onDurationKnown] — called once the player reaches `STATE_READY` — corrects
 * [ListenClock] via [ListenClock.updateDuration] (which does NOT reset progress, unlike
 * [ListenClock.start]) and, if currently playing, reschedules the pending timer against the
 * now-real threshold; a track that turns out to be well under the 240s default cap must not wait
 * out the default before its "played" scrobble fires.
 *
 * ## Repeat-one and process death (plan Review Focus 2, 5)
 * Every call to [onMediaItemTransition] — whatever reason Media3 reports it for, repeat-one
 * included — starts a fresh play-through unconditionally: a new [TrackRef]?, a fresh
 * [ListenClock.start], `nowPlayingSent` reset. A play-through that never reaches its threshold
 * before the process dies is simply lost — there is no persistence of in-progress listening, only
 * of scrobbles that were ATTEMPTED and failed ([ScrobbleQueue]) — which is the correct amount of
 * durability: re-crossing the threshold on the next launch would double-count a play-through that
 * never actually finished being listened to.
 */
class Scrobbler(
    private val scope: CoroutineScope,
    private val clock: ListenClock,
    private val sourceExists: (sourceId: String) -> Boolean,
    private val sender: suspend (TrackRef, submission: Boolean, timeMs: Long?) -> ScrobbleResult,
    private val queue: ScrobbleQueue,
    private val flush: suspend (sourceId: String) -> Unit,
    private val enqueueFlush: () -> Unit,
    private val scheduler: Scheduler,
) {

    private var currentTrack: TrackRef? = null
    private var nowPlayingSent = false
    private var isPlaying = false
    private var cancelPending: (() -> Unit)? = null
    private var released = false

    /** Bumped on every play-through so a played-timer callback that somehow outlives [cancelTimer]
     *  (it never should — [Scheduler.postDelayed]'s cancel is synchronous on the same thread every
     *  caller here uses) can tell it no longer belongs to the current one. Defence in depth, not
     *  the primary guard. */
    private var generation = 0

    /**
     * A media-item transition — ANY reason, including repeat-one wrapping to the same item, which
     * Media3 reports as a genuine `onMediaItemTransition` call (design spec §3). [track] is
     * already resolved (null for a local item, OR one whose uri did not parse, OR — see below —
     * one whose source no longer exists); this method does no URI parsing itself.
     *
     * A [track] whose [TrackRef.sourceId] [sourceExists] says is gone is treated exactly like a
     * local item: nothing is scrobbled for this play-through. This is deliberately checked HERE,
     * once, rather than left for [sender] to discover per call — a play-through for a removed
     * account must schedule no timer and attempt no "now playing" at all, not merely have every
     * attempt quietly fail.
     */
    fun onMediaItemTransition(track: TrackRef?, durationMs: Long) {
        cancelTimer()
        generation++
        isPlaying = false
        currentTrack = track?.takeIf { sourceExists(it.sourceId) }
        nowPlayingSent = false
        currentTrack?.let { clock.start(durationMs) }
    }

    /** `Player.Listener.onIsPlayingChanged`. Drives [ListenClock.onPlaying]/[ListenClock.onPaused]
     *  and, only while there is an eligible [currentTrack], sends "now playing" once and
     *  (re)schedules the "played" timer on every transition into playing. */
    fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        val track = currentTrack ?: return
        if (playing) {
            clock.onPlaying()
            if (!nowPlayingSent) sendNowPlaying(track)
            scheduleOrFirePlayed(track)
        } else {
            clock.onPaused()
            cancelTimer()
        }
    }

    /**
     * The player learned (or re-confirmed) the current item's real duration — call on
     * `Player.Listener.onPlaybackStateChanged(Player.STATE_READY)`. A no-op with no eligible
     * [currentTrack]. See the class KDoc, "Duration arriving late".
     */
    fun onDurationKnown(durationMs: Long) {
        if (released) return
        val track = currentTrack ?: return
        clock.updateDuration(durationMs)
        if (isPlaying) scheduleOrFirePlayed(track)
    }

    /** Cancels any pending "played" timer. Does not cancel [scope] — that belongs to whoever
     *  constructed this class (`PlaybackService.onDestroy`), the same division of ownership
     *  [com.kaislate.veldtplayer.data.library.scan.MediaStoreWatcher] uses for its own scope. */
    fun release() {
        released = true
        cancelTimer()
    }

    private fun sendNowPlaying(track: TrackRef) {
        nowPlayingSent = true // set synchronously: a rapid duplicate onIsPlayingChanged(true) must not double-send
        scope.launch {
            if (sender(track, false, null) is ScrobbleResult.Ok) flush(track.sourceId)
        }
    }

    /** Cancels whatever is pending, then — unless already [ListenClock.sent] — either schedules a
     *  fresh timer for [ListenClock.remainingToThresholdMs] or, if that is null because the
     *  threshold has ALREADY been crossed (e.g. [onDurationKnown] just revealed a much shorter
     *  track than the unknown-duration default assumed), sends immediately. */
    private fun scheduleOrFirePlayed(track: TrackRef) {
        cancelTimer()
        if (clock.sent) return
        val remaining = clock.remainingToThresholdMs()
        if (remaining == null) {
            if (clock.crossed()) trySendPlayed(track, generation)
            return
        }
        val expectedGeneration = generation
        cancelPending = scheduler.postDelayed(remaining) { trySendPlayed(track, expectedGeneration) }
    }

    private fun cancelTimer() {
        cancelPending?.invoke()
        cancelPending = null
    }

    private fun trySendPlayed(track: TrackRef, expectedGeneration: Int) {
        if (released || expectedGeneration != generation || clock.sent || !clock.crossed()) return
        clock.markSent() // synchronous: the one guard against ever sending "played" twice
        val timeMs = clock.startedAtWallMs
        scope.launch {
            when (val result = sender(track, true, timeMs)) {
                ScrobbleResult.Ok -> flush(track.sourceId)
                ScrobbleResult.Unreachable -> {
                    queue.add(QueuedScrobble(track.sourceId, track.externalId, timeMs))
                    enqueueFlush()
                }
                is ScrobbleResult.Rejected ->
                    if (result.error.meansCredentialsWontWork) {
                        queue.add(QueuedScrobble(track.sourceId, track.externalId, timeMs))
                        queue.setAuthBlocked(track.sourceId, true)
                    }
                // else: some other rejection (e.g. 70) — dropped, resending cannot change it.
            }
        }
    }
}
