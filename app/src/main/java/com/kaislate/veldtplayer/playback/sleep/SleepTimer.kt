// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.sleep

import com.kaislate.veldtplayer.playback.scrobble.Scheduler

/** What the sleep timer is doing. Never persisted (spec §4): a process death simply ends it. */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Stops at [endsAtElapsedMs], on the `SystemClock.elapsedRealtime` clock — the one clock
     *  the service and the UI share that a wall-clock change cannot move. */
    data class Timed(val endsAtElapsedMs: Long) : SleepTimerState

    /** Stops when the playing track ends. */
    data object EndOfTrack : SleepTimerState
}

/** [state], plus the whole minutes left of a [SleepTimerState.Timed] (rounded UP, so the last
 *  seconds read "1 min", never "0 min") — what the notification's label is built from. */
data class SleepTimerStatus(val state: SleepTimerState, val remainingMinutes: Int?)

/**
 * The service-side sleep timer (0.9.2 spec §4). It lives in `PlaybackService`, so it keeps
 * running with the UI gone; the UI only sends it commands and shows its [SleepTimerStatus].
 *
 * **The fade.** Over the last [FADE_MS] of a timed timer the output is faded LINEARLY from 1 to 0
 * through [fade] — which is `GainStage.setFade`, the one owner of the final gain — then the
 * player is paused and the fade restored to 1, so the next press of play is at full volume. For
 * "end of this track" the fade runs over the track's own last [FADE_MS] (or the whole track, if
 * it is shorter), and the PLAYER does the pause, exactly at the track's end, through
 * `pauseAtEndOfMediaItems` — no timer could hit a gapless boundary as precisely.
 *
 * "End of this track" follows the user: a skip makes the new track the one it stops after. That
 * is the reading of the option the pause mechanism gives for free, and the one a half-asleep
 * user who skips a song they dislike would expect.
 *
 * Everything that touches Android is behind [Playback], [Scheduler] and [now], so the timing
 * rules — which are the whole feature — are asserted on the JVM against a fake clock.
 *
 * Threading: main thread only, like the player it drives.
 */
class SleepTimer(
    private val now: () -> Long,
    private val scheduler: Scheduler,
    private val playback: Playback,
    private val fade: (Float) -> Unit,
    private val onUpdate: (SleepTimerStatus) -> Unit,
) {

    /** The slice of the player this needs. `PlaybackService` adapts its `ExoPlayer`. */
    interface Playback {
        val positionMs: Long

        /** The current item's duration, or a value ≤ 0 when it is not known yet. */
        val durationMs: Long
        val isPlaying: Boolean
        fun pause()
        fun setPauseAtEndOfItem(enabled: Boolean)
    }

    var state: SleepTimerState = SleepTimerState.Off
        private set

    private var cancelTick: (() -> Unit)? = null
    private var lastPublished: SleepTimerStatus? = null

    /** A timer of [minutes], replacing any running one. */
    fun setMinutes(minutes: Int) {
        require(minutes in MIN_MINUTES..MAX_MINUTES) { "sleep timer minutes out of range: $minutes" }
        start(SleepTimerState.Timed(now() + minutes * MINUTE_MS))
    }

    /** Stop at the end of the playing track, replacing any running timer. */
    fun setEndOfTrack() = start(SleepTimerState.EndOfTrack)

    /**
     * Adds [minutes]. "End of this track" becomes a timed timer that ends [minutes] after the
     * track would have: the user asked for MORE time, and the track's end is the time they had.
     */
    fun extend(minutes: Int = EXTEND_MINUTES) {
        when (val s = state) {
            SleepTimerState.Off -> Unit
            is SleepTimerState.Timed -> start(SleepTimerState.Timed(s.endsAtElapsedMs + minutes * MINUTE_MS))
            SleepTimerState.EndOfTrack -> {
                val left = trackRemainingMs() ?: 0L
                start(SleepTimerState.Timed(now() + left + minutes * MINUTE_MS))
            }
        }
    }

    /** Stops the timer without pausing; the volume comes straight back. */
    fun cancel() = finish(pause = false)

    /**
     * The player paused itself at the end of an item (`PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA
     * _ITEM`) — for "end of this track", that IS the timer going off.
     */
    fun onPausedAtEndOfItem() {
        if (state == SleepTimerState.EndOfTrack) finish(pause = false)
    }

    /** The queue ran out. "End of this track" has nothing left to wait for. */
    fun onPlaybackEnded() {
        if (state == SleepTimerState.EndOfTrack) finish(pause = false)
    }

    /** Service teardown: stop ticking. Nothing is restored — the stage goes with the player. */
    fun release() {
        cancelTick?.invoke()
        cancelTick = null
    }

    private fun start(next: SleepTimerState) {
        cancelTick?.invoke()
        state = next
        playback.setPauseAtEndOfItem(next == SleepTimerState.EndOfTrack)
        tick()
    }

    private fun tick() {
        cancelTick = null
        val delay = when (val s = state) {
            SleepTimerState.Off -> return
            is SleepTimerState.Timed -> {
                val left = s.endsAtElapsedMs - now()
                if (left <= 0) {
                    finish(pause = true)
                    return
                }
                fade(fadeFor(left, FADE_MS))
                nextTick(left, FADE_MS)
            }
            SleepTimerState.EndOfTrack -> {
                val left = trackRemainingMs()
                val window = playback.durationMs.coerceAtMost(FADE_MS)
                if (left == null || window <= 0) {
                    fade(1f)
                    COARSE_TICK_MS
                } else {
                    fade(fadeFor(left, window))
                    nextTick(left, window)
                }
            }
        }
        publish()
        cancelTick = scheduler.postDelayed(delay) { tick() }
    }

    private fun finish(pause: Boolean) {
        cancelTick?.invoke()
        cancelTick = null
        val wasRunning = state != SleepTimerState.Off
        state = SleepTimerState.Off
        if (pause && playback.isPlaying) playback.pause()
        if (wasRunning) {
            playback.setPauseAtEndOfItem(false)
            // AFTER the pause: restoring first would un-fade the last moment before it.
            fade(1f)
        }
        publish()
    }

    private fun trackRemainingMs(): Long? {
        val duration = playback.durationMs
        if (duration <= 0) return null
        return (duration - playback.positionMs).coerceAtLeast(0)
    }

    private fun publish() {
        val status = SleepTimerStatus(
            state = state,
            remainingMinutes = (state as? SleepTimerState.Timed)?.let { t ->
                val left = (t.endsAtElapsedMs - now()).coerceAtLeast(0)
                ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
            },
        )
        if (status == lastPublished) return
        lastPublished = status
        onUpdate(status)
    }

    companion object {
        /** The fade length (spec §4). */
        const val FADE_MS = 30_000L

        /** Custom durations (spec §4): 1–180 minutes. */
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 180

        /** What "+10 min" adds. */
        const val EXTEND_MINUTES = 10

        /** The presets on the sheet (spec §4). */
        val PRESET_MINUTES = listOf(15, 30, 45, 60)

        internal const val MINUTE_MS = 60_000L

        /** Outside the fade the timer only has to notice the fade starting and the minute label
         *  changing; a second is plenty. */
        internal const val COARSE_TICK_MS = 1_000L

        /** Inside the fade: the stage ramps between ticks, so this only bounds the step size. */
        internal const val FADE_TICK_MS = 100L

        /** Linear: 1 at the start of the window, 0 at its end. */
        fun fadeFor(remainingMs: Long, windowMs: Long): Float =
            (remainingMs.toFloat() / windowMs).coerceIn(0f, 1f)

        /**
         * Coarse until the fade window, then fine — but never past the moment it should act. At
         * zero (end of track, waiting for the player's own pause) it stays at the fine rate.
         */
        private fun nextTick(remainingMs: Long, windowMs: Long): Long = when {
            remainingMs > windowMs -> minOf(COARSE_TICK_MS, remainingMs - windowMs)
            remainingMs > 0 -> minOf(FADE_TICK_MS, remainingMs)
            else -> FADE_TICK_MS
        }
    }
}
