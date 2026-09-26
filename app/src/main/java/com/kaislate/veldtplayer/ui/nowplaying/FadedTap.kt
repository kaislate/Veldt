// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

/**
 * Whether a tap on a control that ambient mode can fade should ONLY wake the chrome rather than
 * do its own thing: decided by whether the chrome was faded when the finger went DOWN
 * ([fadedAtDown]), not when the click fires.
 *
 * **Why the down and not the click.** The root's wake watcher sees the same down on the Initial
 * pass and bumps the idle clock; the chrome starts fading back in on the next frame, so by the
 * time a `clickable` fires on the UP (~100–200 ms later) the chrome already reads as live. Read
 * at the click, a tap on faded artwork both woke the chrome AND opened lyrics — measured on
 * device (Task 6, a 160 ms tap). The collapse button had the same race.
 *
 * [fadedAtDown] is null when the click did not come from a pointer (a key press, an
 * accessibility action): then there was no down to latch, and the current state [fadedNow] is
 * the only answer — and the right one, since nothing has woken the chrome in between.
 */
fun tapOnlyWakes(fadedAtDown: Boolean?, fadedNow: Boolean): Boolean = fadedAtDown ?: fadedNow

/**
 * Holds [tapOnlyWakes]'s `fadedAtDown` between a pointer's down and the click it produces.
 *
 * Lifecycle per pointer gesture: [down] at the down; [consume] at the click, if the gesture
 * becomes one (answers, then clears); [gestureEnded] once the gesture is over, strictly AFTER any
 * click it produced (clears whatever is left). So a gesture that never clicked — a partial drag,
 * a cancelled press — leaves nothing behind, and a later non-pointer activation never reads a
 * stale down-time value: it gets `null`, i.e. the live state. Main-thread only, like the pointer
 * input and click handlers that drive it.
 */
class FadedTapLatch {
    private var fadedAtDown: Boolean? = null

    fun down(faded: Boolean) {
        fadedAtDown = faded
    }

    fun consume(fadedNow: Boolean): Boolean = tapOnlyWakes(fadedAtDown, fadedNow).also { fadedAtDown = null }

    /** The pointer gesture that [down] started is over; anything it latched and no click consumed
     *  is stale from here on. */
    fun gestureEnded() {
        fadedAtDown = null
    }
}
