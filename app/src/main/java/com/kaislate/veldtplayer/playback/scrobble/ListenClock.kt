// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.scrobble

/**
 * The wall-clock moment (ms since epoch) a play-through started — captured once, at [ListenClock
 * .start], and carried unchanged into a "played" scrobble's `time` parameter (design spec §3), so
 * a scrobble delivered late (queued, then flushed) still reports when the track was actually
 * heard, not when the network call finally succeeded.
 */
data class ScrobbleStart(val wallMs: Long)

/**
 * Decides when one play-through of a track counts as "played" (network spec §7.4, design spec
 * §3). Pure and deliberately Media3-free (design spec §6: "the listening clock is a pure class"):
 * a `Player.Listener` (Task 3's `Scrobbler`) drives it with plain calls, so it is testable with a
 * fake clock and carries no Android/Media3 dependency of its own. [UNKNOWN_DURATION] mirrors
 * `androidx.media3.common.C.TIME_UNSET` (`Long.MIN_VALUE + 1`) by value rather than by import.
 *
 * [now] supplies monotonic elapsed time for accumulating *listened* wall time — production
 * passes `SystemClock.elapsedRealtime`, never [System.currentTimeMillis], because the accumulator
 * must never jump when the device's clock is corrected mid-play. [wallClock] is a SEPARATE clock,
 * consulted only at [start], and is what backs [startedAtWallMs] — the two must not be the same
 * call, or a clock correction mid-play would corrupt the accumulator instead of only the
 * timestamp a delayed scrobble reports.
 *
 * Seeking a track is never an input to this class at all: there is no seek method, and only
 * [onPlaying]/[onPaused] move the accumulator. That omission is what keeps "played" meaning
 * *listened*, never *reached a position* (plan Review Focus 1) — a user who seeks to 90% of a
 * track and never presses play again has listened for zero milliseconds.
 */
class ListenClock(
    private val now: () -> Long,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
) {

    private var durationMs: Long = 0
    private var accumulatedMs: Long = 0
    private var playingSinceMs: Long? = null
    private var scrobbleStart: ScrobbleStart = ScrobbleStart(0L)

    /** Whether a "played" scrobble has already gone out for the current play-through. */
    var sent: Boolean = false
        private set

    /** [ScrobbleStart.wallMs] this play-through's [start] recorded. */
    val startedAtWallMs: Long get() = scrobbleStart.wallMs

    /**
     * Begins a new play-through: resets the listened accumulator and [sent], and — from the
     * separate [wallClock] — captures [startedAtWallMs]. Called on every media-item transition,
     * including repeat-one wrapping to the same item (design spec §3: "a new play-through starts
     * on a media-item transition, including repeat-one wrapping to the same item").
     */
    fun start(durationMs: Long) {
        this.durationMs = durationMs
        accumulatedMs = 0L
        playingSinceMs = null
        sent = false
        scrobbleStart = ScrobbleStart(wallClock())
    }

    /** Playback has (re)started. Idempotent: a second call while already playing is a no-op —
     *  otherwise a duplicate `onIsPlayingChanged(true)` would re-anchor the running span and
     *  silently drop whatever had already elapsed since the first one. */
    fun onPlaying() {
        if (playingSinceMs == null) playingSinceMs = now()
    }

    /** Playback paused (or stopped). Folds the just-finished playing span into the accumulator
     *  and stops the clock; a subsequent [listenedMs] no longer moves until [onPlaying] again. */
    fun onPaused() {
        val since = playingSinceMs ?: return
        accumulatedMs += now() - since
        playingSinceMs = null
    }

    /** Total wall time actually spent playing this play-through. Seeks never move this. */
    fun listenedMs(): Long {
        val since = playingSinceMs
        return if (since != null) accumulatedMs + (now() - since) else accumulatedMs
    }

    /**
     * `min(duration / 2, 240 000)` — or null when this play-through must NEVER send "played"
     * (duration under [MIN_DURATION_MS]; design spec §3). An unknown duration (`<= 0`, or
     * [UNKNOWN_DURATION]) is checked FIRST and is never treated as "too short": it resolves to
     * the [DEFAULT_THRESHOLD_MS] cap instead, per design spec §3's explicit carve-out.
     */
    fun thresholdMs(): Long? {
        if (durationMs <= 0 || durationMs == UNKNOWN_DURATION) return DEFAULT_THRESHOLD_MS
        if (durationMs < MIN_DURATION_MS) return null
        return minOf(durationMs / 2, DEFAULT_THRESHOLD_MS)
    }

    /**
     * [thresholdMs] minus [listenedMs] while there is still ground to cover; null when this
     * play-through will never cross (see [thresholdMs]) or has already crossed.
     */
    fun remainingToThresholdMs(): Long? {
        val threshold = thresholdMs() ?: return null
        val remaining = threshold - listenedMs()
        return remaining.takeIf { it > 0 }
    }

    /** Whether accumulated listened time has reached [thresholdMs]. Always false for a
     *  play-through that will never cross (a track under [MIN_DURATION_MS]). */
    fun crossed(): Boolean {
        val threshold = thresholdMs() ?: return false
        return listenedMs() >= threshold
    }

    /** Records that the "played" scrobble for this play-through has gone out, so a caller can
     *  send it at most once per play-through. */
    fun markSent() {
        sent = true
    }

    companion object {
        /** Design spec §3: tracks shorter than this never send "played". */
        const val MIN_DURATION_MS: Long = 30_000L

        /** Design spec §3: the cap on the "played" threshold, and what an unknown duration uses. */
        const val DEFAULT_THRESHOLD_MS: Long = 240_000L

        /** `androidx.media3.common.C.TIME_UNSET`, mirrored by value (pinned fact: `Long.MIN_VALUE
         *  + 1`) rather than by import — see the class KDoc on why this stays Media3-free. */
        const val UNKNOWN_DURATION: Long = Long.MIN_VALUE + 1
    }
}
