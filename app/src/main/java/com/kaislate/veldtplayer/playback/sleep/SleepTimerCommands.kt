// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.sleep

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** One thing a controller asked the sleep timer to do. */
sealed interface SleepTimerRequest {
    data class SetMinutes(val minutes: Int) : SleepTimerRequest
    data object SetEndOfTrack : SleepTimerRequest
    data object Extend : SleepTimerRequest
    data object Cancel : SleepTimerRequest
}

/**
 * The wire format between `PlaybackConnection` (the app's controller) and `PlaybackService`
 * (spec §4): three custom `SessionCommand`s in, and the timer's state out as session extras.
 *
 * Session extras rather than a process-wide singleton because they are Media3's own channel from a
 * session to every controller, delivered on connect and on change — the same path the app's
 * `MediaController` already uses for everything else it knows about the player.
 *
 * Both directions are decoded defensively: a command is refused unless its arguments are in range
 * (any controller can send one), and unreadable extras read as [SleepTimerState.Off].
 */
object SleepTimerCommands {

    const val ACTION_SET = "com.kaislate.veldtplayer.sleep.SET"
    const val ACTION_EXTEND = "com.kaislate.veldtplayer.sleep.EXTEND"
    const val ACTION_CANCEL = "com.kaislate.veldtplayer.sleep.CANCEL"

    private const val ARG_MINUTES = "minutes"
    private const val ARG_END_OF_TRACK = "end_of_track"

    private const val EXTRA_MODE = "com.kaislate.veldtplayer.sleep.MODE"
    private const val EXTRA_ENDS_AT = "com.kaislate.veldtplayer.sleep.ENDS_AT"
    private const val MODE_TIMED = "timed"
    private const val MODE_END_OF_TRACK = "end_of_track"

    val SET = SessionCommand(ACTION_SET, Bundle.EMPTY)
    val EXTEND = SessionCommand(ACTION_EXTEND, Bundle.EMPTY)
    val CANCEL = SessionCommand(ACTION_CANCEL, Bundle.EMPTY)

    fun minutesArgs(minutes: Int): Bundle = Bundle().apply { putInt(ARG_MINUTES, minutes) }

    fun endOfTrackArgs(): Bundle = Bundle().apply { putBoolean(ARG_END_OF_TRACK, true) }

    /** The request [action] + [args] carry, or null for anything not a valid sleep command. */
    fun parse(action: String, args: Bundle): SleepTimerRequest? = when (action) {
        ACTION_SET -> when {
            args.getBoolean(ARG_END_OF_TRACK, false) -> SleepTimerRequest.SetEndOfTrack
            else -> args.getInt(ARG_MINUTES, 0)
                .takeIf { it in SleepTimer.MIN_MINUTES..SleepTimer.MAX_MINUTES }
                ?.let(SleepTimerRequest::SetMinutes)
        }
        ACTION_EXTEND -> SleepTimerRequest.Extend
        ACTION_CANCEL -> SleepTimerRequest.Cancel
        else -> null
    }

    /** [state] as session extras. [SleepTimerState.Off] is an empty bundle. */
    fun toExtras(state: SleepTimerState): Bundle = Bundle().apply {
        when (state) {
            SleepTimerState.Off -> Unit
            is SleepTimerState.Timed -> {
                putString(EXTRA_MODE, MODE_TIMED)
                putLong(EXTRA_ENDS_AT, state.endsAtElapsedMs)
            }
            SleepTimerState.EndOfTrack -> putString(EXTRA_MODE, MODE_END_OF_TRACK)
        }
    }

    fun fromExtras(extras: Bundle?): SleepTimerState = when (extras?.getString(EXTRA_MODE)) {
        MODE_TIMED ->
            if (extras.containsKey(EXTRA_ENDS_AT)) SleepTimerState.Timed(extras.getLong(EXTRA_ENDS_AT))
            else SleepTimerState.Off
        MODE_END_OF_TRACK -> SleepTimerState.EndOfTrack
        else -> SleepTimerState.Off
    }

    /**
     * The media notification's label for [status] (spec §4: "the remaining minutes as its label
     * where the platform renders labels"). Null when there is no timer, and so no button.
     */
    fun notificationLabel(status: SleepTimerStatus): String? = when (status.state) {
        SleepTimerState.Off -> null
        is SleepTimerState.Timed -> "Sleep in ${status.remainingMinutes ?: 0} min · tap to cancel"
        SleepTimerState.EndOfTrack -> "Sleep at end of track · tap to cancel"
    }
}
