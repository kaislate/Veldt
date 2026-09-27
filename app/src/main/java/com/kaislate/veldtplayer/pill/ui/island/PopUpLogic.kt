// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (ui/island/PopUpLogic.kt), GPL-3.0-or-later, same author.
//
// Adapted for Veldt: Wisp's file also carried `PopUpColors`, which resolved the wave's
// colour against `ColorExtractor`/`DominantColors` (album-art palette extraction). Spec §2
// drops that in favour of Veldt's own solved `ArtSeed` tones (`backdropText`/`backdropMarks`),
// so only the clock-and-gesture logic below — `Playhead` — is ported here. The pill's
// colour resolution against `ArtSeed` is done where the overlay UI is ported.

package com.kaislate.veldtplayer.pill.ui.island

/**
 * The decision core behind the expanded panel: where the playhead is, how far along the
 * track that is, and how to write it down.
 *
 * Nothing here draws, animates or reads a clock of its own — every input arrives as a
 * parameter, so the whole of it is assertable from a plain JVM unit test. The composable
 * that sits on top owns the recomposition, the ticker and the gesture plumbing; it asks
 * these functions what the answer is.
 */

/**
 * Where the track has got to, derived rather than observed.
 *
 * A media session publishes its position only when something changes — a seek, a pause, a
 * track boundary. Between those announcements it says nothing at all, so a UI that renders
 * only what it was told shows a progress bar frozen for seconds at a stretch. The panel
 * therefore extrapolates from the last announcement, and [TICK_MS] is how often it asks.
 */
object Playhead {

    /**
     * How often the panel recomputes the extrapolated position.
     *
     * Twice a second: fast enough that the seconds readout never visibly skips a value,
     * slow enough that the cost of the timer is irrelevant.
     */
    const val TICK_MS = 500L

    /**
     * How far a single vertical drag event must travel upward before it counts as a
     * dismissal. Negative because the y-axis grows downward.
     */
    const val CLOSE_SWIPE_PX = -20f

    /**
     * The position the track is at *now*, extrapolated from the last position the session
     * reported.
     *
     * Two details are load-bearing and look arbitrary until you know what they prevent:
     *
     * **The reference clock is `elapsedRealtime`, not wall-clock time.** A `PlaybackState`
     * timestamps its position against the monotonic clock. Measuring the gap in wall time
     * makes the playhead lurch whenever the device syncs NTP or the user crosses a time
     * zone; measuring it monotonically cannot.
     *
     * **[advancing] comes from the `PlaybackState` object itself**, not from the playback
     * state we observe separately elsewhere. The two arrive through different flows and can
     * briefly disagree, and the object is the one that is consistent with [reportedAtMs] —
     * the timestamp being extrapolated from.
     *
     * The speed multiplier is honoured rather than assumed to be `1.0`, so a podcast at
     * 1.5× tracks correctly and a rewinding session walks backwards.
     *
     * @param reportedMs the position the session last announced.
     * @param reportedAtMs the `elapsedRealtime` at which it announced it.
     * @param speed the playback speed multiplier; may be negative while rewinding.
     * @param advancing whether the session's own state says the position is moving.
     * @param nowMs the current `elapsedRealtime`, same base as [reportedAtMs].
     * @param durationMs the track length, or `0` or less when it is unknown — a live stream
     *   has no end to clamp against, and clamping an unknown length to zero would peg the
     *   bar at the start, so an unknown duration is returned unclamped.
     */
    fun positionMs(
        reportedMs: Long,
        reportedAtMs: Long,
        speed: Float,
        advancing: Boolean,
        nowMs: Long,
        durationMs: Long,
    ): Long {
        val extrapolated =
            if (advancing) reportedMs + ((nowMs - reportedAtMs) * speed).toLong()
            else reportedMs
        return if (durationMs > 0L) extrapolated.coerceIn(0L, durationMs) else extrapolated
    }

    /**
     * [positionMs] as a fraction of the track, for the progress bar.
     *
     * `0f` when the duration is unknown: with no end there is no fraction to report, and
     * an empty bar is a better lie than a dividing-by-zero one.
     */
    fun progress(positionMs: Long, durationMs: Long): Float =
        if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /**
     * A duration as `m:ss` — `4:07`, `93:20`. Seconds truncate rather than round, so the
     * readout matches the elapsed second the listener has actually heard, and negative
     * inputs read as zero.
     *
     * There is deliberately no hour field. The panel is narrow, and tracks over an hour are
     * rare enough that letting the minutes run past 60 is preferable to widening the layout
     * for everyone.
     */
    fun formatTime(ms: Long): String {
        val totalSeconds = (if (ms > 0L) ms else 0L) / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "$minutes:" + seconds.toString().padStart(2, '0')
    }

    /**
     * Whether one vertical drag event counts as a close gesture: strictly more than
     * [CLOSE_SWIPE_PX] of upward movement *within that single event*.
     *
     * Per-event rather than accumulated, on purpose. A slow, deliberate upward drag never
     * exceeds the threshold in any one event and leaves the panel open; a flick does it in
     * one and closes it.
     */
    fun isCloseSwipe(dragDeltaPx: Float): Boolean = dragDeltaPx < CLOSE_SWIPE_PX
}
