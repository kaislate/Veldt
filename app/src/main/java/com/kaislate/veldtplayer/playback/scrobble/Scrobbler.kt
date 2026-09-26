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
 * methods below.
 *
 * **[scope]'s dispatcher decides where [sender]/[queue]/[flush] run — never this class's
 * business** (review fix round 1, item 5): every `scope.launch { ... }` below is the ENTIRETY of
 * this class's "launched work", and clock/player-state mutations ([clock], [currentTrack],
 * [isPlaying], [nowPlayingSent]) all happen SYNCHRONOUSLY in the methods below, on whatever
 * thread calls them — never inside a launch. Production (`PlaybackService`) supplies a
 * `Dispatchers.IO`-backed scope so the queue's file I/O and the network call never touch the
 * player's own thread; a test supplies whatever it needs for determinism.
 *
 * **Fix round 2, finding 3 — [queue] is FILE I/O, never called synchronously.** Every
 * [ScrobbleQueue] call ([queue].add, `isAuthBlocked`, `markInFlight`, `remove`,
 * `setAuthBlocked`, `clearInFlight`) happens INSIDE a `scope.launch { ... }` below, never in the
 * synchronous body of a public method — the write-ahead guarantee (finding 4, fix round 1) is
 * about ORDERING relative to [sender] (queued before the network attempt), not about which
 * thread does the write; on [scope]'s `Dispatchers.IO`, none of it touches the calling
 * (player/main) thread. Only cheap, in-memory decisions — [clock] reads, [generation], the
 * [QueuedScrobble] value itself — happen synchronously.
 *
 * ## Now playing
 * The first "started playing" moment of a play-through — either [onIsPlayingChanged]`(true)`, OR
 * [onMediaItemTransition] itself when the player was ALREADY playing (see below) — sends
 * `submission=false`, fire-and-forget: the result is ignored except that [ScrobbleResult.Ok]
 * triggers a piggyback [flush] (design spec §5). Never queued on failure — design spec §3.
 * Skipped entirely while the source is auth-blocked (review fix round 1, item 6 — controller
 * ruling): no point attempting a live call the account's own queue already knows will fail.
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
 * **Write-ahead** (review fix round 1, item 4 — controller ruling): the queue entry is added
 * BEFORE the send is attempted, not after it fails. A process death or a teardown cancellation
 * mid-request must not lose a "played" that this class had already committed to — see
 * [trySendPlayed]. [ScrobbleResult.Ok] removes it; a non-credential [ScrobbleResult.Rejected]
 * (dropped — resending cannot change it) also removes it; [ScrobbleResult.Unreachable] and a
 * credential [ScrobbleResult.Rejected] leave it queued, exactly as before. While the source is
 * already auth-blocked, the send is never attempted at all — the write-ahead entry alone IS the
 * outcome (review fix round 1, item 6).
 *
 * **In-flight marking** (fix round 2, finding 2 — double delivery): between write-ahead adding
 * the entry and the live send resolving, a [ScrobbleQueue.markInFlight]/[ScrobbleQueue
 * .clearInFlight] pair tells a CONCURRENT [com.kaislate.veldtplayer.data.scrobble.ScrobbleFlusher]
 * (a piggyback from some other successful request, the retry worker, a sync) to skip this entry
 * rather than sending it a second time before this live send's own outcome is known.
 *
 * ## Auto-advance and repeat-one make no `onIsPlayingChanged` call (review fix round 1, item 1 —
 * CRITICAL fix)
 * Media3 fires `onIsPlayingChanged` only when the PLAYING STATE actually changes. Gapless
 * auto-advance, a repeat-one wrap, and a skip into already-buffered audio all keep the player
 * playing straight through the transition — no `onIsPlayingChanged` event follows, ever, for that
 * new item. [onMediaItemTransition] therefore takes the player's CURRENT `isPlaying` at transition
 * time and, when true, runs the exact same "started playing" logic (clock.onPlaying, now-playing,
 * schedule the played timer) immediately, inline — not "wait for a play event that will never
 * come". This is also what makes every `onMediaItemTransition` — whatever reason Media3 reports
 * it for, repeat-one included — a fresh play-through unconditionally (design spec §3): a new
 * [TrackRef]?, a fresh [ListenClock.start], `nowPlayingSent` reset, and — if already playing — an
 * immediate now-playing send and timer schedule for THIS item, with no separate event required.
 *
 * ## Duration arriving late — and for WHICH item (fix round 2, finding 1 — IMPORTANT fix)
 * `onMediaItemTransition` commonly fires before Media3 knows the item's duration
 * (`C.TIME_UNSET`). [onDurationKnown] — called once the player reaches `STATE_READY`, and again on
 * `onTimelineChanged` (the facade calls both) — corrects [ListenClock] via
 * [ListenClock.updateDuration] (which does NOT reset progress, unlike [ListenClock.start]) and, if
 * currently playing, reschedules the pending timer against the now-real threshold; a track that
 * turns out to be well under the 240s default cap must not wait out the default before its
 * "played" scrobble fires.
 *
 * **[onDurationKnown] takes the [TrackRef]? the duration is FOR, and ignores it unless it equals
 * [currentTrack].** Media3 delivers `onTimelineChanged` for a queue replacement BEFORE
 * `onMediaItemTransition`, and by then `player.currentMediaItem` already reports the NEW item —
 * so a naive "just apply whatever duration arrives" would apply the NEXT item's (possibly much
 * shorter) duration to the item STILL playing, corrupting its threshold and potentially firing a
 * false "played" for a track the user never actually finished (e.g. a 300s track at 100s listened,
 * replaced by an album whose first track is 180s: a wrongly-applied 90s threshold would cross
 * immediately). The real transition, when it follows, starts the new item's OWN play-through with
 * its own duration regardless — this check only prevents the stale-item corruption in between.
 *
 * ## Process death (plan Review Focus 5)
 * A play-through that never reaches its threshold before the process dies is simply lost — there
 * is no persistence of in-progress listening, only of scrobbles that were ATTEMPTED (write-ahead
 * queued) — which is the correct amount of durability: re-crossing the threshold on the next
 * launch would double-count a play-through that never actually finished being listened to.
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
     * [isPlaying] is the player's playing state AT THIS TRANSITION, from the caller (see the class
     * KDoc's CRITICAL fix note) — when true, this method runs the "started playing" logic for the
     * new item immediately, inline, because no separate [onIsPlayingChanged] call is coming.
     *
     * A [track] whose [TrackRef.sourceId] [sourceExists] says is gone is treated exactly like a
     * local item: nothing is scrobbled for this play-through. This is deliberately checked HERE,
     * once, rather than left for [sender] to discover per call — a play-through for a removed
     * account must schedule no timer and attempt no "now playing" at all, not merely have every
     * attempt quietly fail.
     */
    fun onMediaItemTransition(track: TrackRef?, durationMs: Long, isPlaying: Boolean) {
        cancelTimer()
        generation++
        this.isPlaying = isPlaying
        val resolved = track?.takeIf { sourceExists(it.sourceId) }
        currentTrack = resolved
        nowPlayingSent = false
        if (resolved == null) return
        clock.start(durationMs)
        if (isPlaying) onStartedPlaying(resolved)
    }

    /** `Player.Listener.onIsPlayingChanged`. Drives [ListenClock.onPlaying]/[ListenClock.onPaused]
     *  and, only while there is an eligible [currentTrack], runs the "started playing" logic (or
     *  folds the elapsed span and cancels the timer, on pause). */
    fun onIsPlayingChanged(playing: Boolean) {
        isPlaying = playing
        val track = currentTrack ?: return
        if (playing) {
            onStartedPlaying(track)
        } else {
            clock.onPaused()
            cancelTimer()
        }
    }

    /** The one place "playback just started, for [track]" is handled — from either an actual
     *  [onIsPlayingChanged]`(true)` or an [onMediaItemTransition] that arrived already playing. */
    private fun onStartedPlaying(track: TrackRef) {
        clock.onPlaying()
        if (!nowPlayingSent) sendNowPlaying(track)
        scheduleOrFirePlayed(track)
    }

    /**
     * The player learned (or re-confirmed) a duration for [track] — call on
     * `Player.Listener.onPlaybackStateChanged(Player.STATE_READY)` AND on `onTimelineChanged`
     * (the facade calls both — review fix round 1, item 3), passing whichever [TrackRef]? the
     * CURRENT `player.currentMediaItem` resolves to at that moment.
     *
     * A no-op with no eligible [currentTrack], AND a no-op when [track] does not equal
     * [currentTrack] (fix round 2, finding 1) — seeing this called for a different item means
     * Media3 has already moved `player.currentMediaItem` on to the NEXT item while THIS class's
     * play-through is still the old one; applying that duration here would corrupt the old
     * item's threshold. See the class KDoc, "Duration arriving late — and for WHICH item".
     */
    fun onDurationKnown(track: TrackRef?, durationMs: Long) {
        if (released) return
        val current = currentTrack ?: return
        if (track != current) return
        clock.updateDuration(durationMs)
        if (isPlaying) scheduleOrFirePlayed(current)
    }

    /** Cancels any pending "played" timer. Does not cancel [scope] — that belongs to whoever
     *  constructed this class (`PlaybackService.onDestroy`), the same division of ownership
     *  [com.kaislate.veldtplayer.data.library.scan.MediaStoreWatcher] uses for its own scope. */
    fun release() {
        released = true
        cancelTimer()
    }

    private fun sendNowPlaying(track: TrackRef) {
        nowPlayingSent = true // set synchronously: a rapid duplicate "started playing" must not double-send
        scope.launch {
            // Review fix round 1, item 6 (controller ruling): an auth-blocked source gets no live
            // attempt at all — the account's own queue already knows this call would just fail.
            // Fix round 2, finding 3: the check itself is FILE I/O, so it runs here, not
            // synchronously in the caller — see the class KDoc.
            if (queue.isAuthBlocked(track.sourceId)) return@launch
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

    /**
     * Write-ahead (review fix round 1, item 4): [queue] gets the entry BEFORE [sender] is ever
     * called — durable (once the write lands) regardless of what happens to the launched send
     * afterward (a teardown cancellation, a process death mid-request). [ScrobbleResult.Ok] and a
     * dropped (non-credential) rejection both remove it again; [ScrobbleResult.Unreachable] and a
     * credential rejection leave it. [ScrobbleQueue.markInFlight]/[ScrobbleQueue.clearInFlight]
     * bracket the send (fix round 2, finding 2) so a concurrent flush does not deliver the same
     * entry a second time while this one is still outstanding.
     *
     * Review fix round 1, item 6 (controller ruling): while the source is already auth-blocked,
     * [sender] is never called at all — the write-ahead entry alone is the correct outcome, with
     * zero network attempts.
     *
     * Fix round 2, finding 3: [clock.markSent][ListenClock.markSent], [generation], and building
     * the plain [QueuedScrobble] VALUE are the only things that happen synchronously, on the
     * caller's thread — every [queue] call (the write-ahead add itself included) is FILE I/O and
     * runs inside [scope]'s launch. See the class KDoc.
     */
    private fun trySendPlayed(track: TrackRef, expectedGeneration: Int) {
        if (released || expectedGeneration != generation || clock.sent || !clock.crossed()) return
        clock.markSent() // synchronous: the one guard against ever sending "played" twice
        val timeMs = clock.startedAtWallMs
        val entry = QueuedScrobble(track.sourceId, track.externalId, timeMs)
        scope.launch {
            queue.add(entry) // write-ahead — see this method's KDoc
            if (queue.isAuthBlocked(track.sourceId)) return@launch // routed straight to the queue; no request
            queue.markInFlight(entry)
            try {
                when (val result = sender(track, true, timeMs)) {
                    ScrobbleResult.Ok -> {
                        queue.remove(entry)
                        flush(track.sourceId)
                    }
                    ScrobbleResult.Unreachable -> enqueueFlush() // stays queued
                    is ScrobbleResult.Rejected ->
                        if (result.error.meansCredentialsWontWork) {
                            queue.setAuthBlocked(track.sourceId, true) // stays queued
                        } else {
                            queue.remove(entry) // dropped: resending cannot change it
                        }
                }
            } finally {
                queue.clearInFlight(entry)
            }
        }
    }
}
