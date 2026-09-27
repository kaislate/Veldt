// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import android.media.session.PlaybackState
import com.kaislate.veldtplayer.pill.PillCommands

/**
 * The pill's and card's command decisions, pulled out of the composables so they are
 * assertable: which command a button sends, where a scrub lands, and whether a skip button is
 * live.
 *
 * Like [PillLayout], the only Android references are `PlaybackState` constants, inlined at
 * compile time, so this runs on a bare JVM.
 */
object PillTransport {

    /** Sends the command [button] stands for. */
    fun press(button: PillButton, commands: PillCommands) {
        when (button) {
            PillButton.PREVIOUS -> commands.previous()
            PillButton.PLAY_PAUSE -> commands.togglePlayPause()
            PillButton.NEXT -> commands.next()
        }
    }

    /**
     * Where a scrub to [fraction] of the track lands, or null when there is nothing to seek:
     * seeking a live stream (or a track whose duration is not known yet) by fraction is
     * meaningless — Wisp's `if (duration > 0L)` guard. The fraction is clamped, since a drag
     * can report a point past either end of the bar.
     */
    fun seekTargetMs(fraction: Float, durationMs: Long): Long? =
        if (durationMs <= 0L) null else (fraction.coerceIn(0f, 1f) * durationMs).toLong()

    /** Scrubs to [fraction] through [commands], if [seekTargetMs] says there is somewhere to go. */
    fun seek(fraction: Float, durationMs: Long, commands: PillCommands) {
        seekTargetMs(fraction, durationMs)?.let(commands::seekTo)
    }

    /**
     * Whether a skip button is live. A session advertising no actions at all (null) is given
     * the benefit of the doubt, as in Wisp; one that advertises actions but not this one gets a
     * disabled button.
     */
    fun canSkipPrevious(actions: Long?): Boolean =
        actions == null || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L

    fun canSkipNext(actions: Long?): Boolean =
        actions == null || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
}
