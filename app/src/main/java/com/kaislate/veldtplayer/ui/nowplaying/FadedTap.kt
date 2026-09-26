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
 * [consume] clears it, so a later non-pointer click never reads a stale latch from an old tap.
 * Main-thread only, like the pointer input and click handlers that drive it.
 */
class FadedTapLatch {
    private var fadedAtDown: Boolean? = null

    fun down(faded: Boolean) {
        fadedAtDown = faded
    }

    fun consume(fadedNow: Boolean): Boolean = tapOnlyWakes(fadedAtDown, fadedNow).also { fadedAtDown = null }
}
