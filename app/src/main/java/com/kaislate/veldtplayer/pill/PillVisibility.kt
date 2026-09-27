// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
//
// New for Veldt (P1.5c): the top-level show/hide decision for the built-in pill, per
// docs/superpowers/specs/2026-09-27-p1.5c-built-in-pill-design.md §3. Not a port of any
// single Veldt Wisp file — it folds together the mode/Wisp-deference/permission gates that
// have no equivalent in Wisp (which has no "defer to a sibling app" concept) with the
// playback-visible gate that Wisp's IslandRules already encodes.

package com.kaislate.veldtplayer.pill

/**
 * The user's three-way choice for the built-in pill.
 *
 * Only [BUILT_IN] can ever show anything; [USE_WISP] and [OFF] both mean "Veldt draws
 * nothing", they differ only in what the settings screen tells the user about why.
 */
enum class PillMode { BUILT_IN, USE_WISP, OFF }

/**
 * Everything [PillVisibility.decide] needs to answer "is the pill on screen right now",
 * gathered into one value so the decision is a single total function of its inputs.
 *
 * @param mode the user's setting.
 * @param forceBuiltIn the "use built-in anyway" override; only meaningful when [wispInstalled].
 * @param wispInstalled whether `com.kaislate.veldt` (Veldt Wisp) is installed on this device.
 * @param overlayGranted `Settings.canDrawOverlays` for Veldt.
 * @param appInForeground Veldt itself is the app on screen — showing the pill over it would
 *   cover the very UI it summarises.
 * @param loaded whether there is a track loaded to show at all (no session, no pill).
 * @param playing whether that track is actively playing (or buffering) right now.
 * @param pausedAtMs when the track was paused, in [nowMs]'s time base, or `null` if it has
 *   never been paused (or nothing is loaded). Ignored while [playing].
 * @param nowMs the current time, same time base as [pausedAtMs].
 * @param hideDelayMs how long a paused pill is allowed to linger before it must hide.
 */
data class PillVisibilityInputs(
    val mode: PillMode,
    val forceBuiltIn: Boolean = false,
    val wispInstalled: Boolean = false,
    val overlayGranted: Boolean = false,
    val appInForeground: Boolean = false,
    val loaded: Boolean = false,
    val playing: Boolean = false,
    val pausedAtMs: Long? = null,
    val nowMs: Long = 0L,
    val hideDelayMs: Long = 0L,
)

/**
 * The pure, total decision of whether the built-in pill belongs on screen right now.
 *
 * Every gate is a conjunction — a single closed gate hides the pill regardless of what any
 * other input says. In order:
 *
 * 1. [PillVisibilityInputs.mode] must be [PillMode.BUILT_IN]. [PillMode.USE_WISP] and
 *    [PillMode.OFF] never show, unconditionally.
 * 2. If Veldt Wisp is installed, the built-in pill stands down ([PillVisibilityInputs.wispInstalled])
 *    *unless* the user has explicitly overridden that with [PillVisibilityInputs.forceBuiltIn] —
 *    the two apps would otherwise both draw a pill for the same playback.
 * 3. The overlay permission must actually be granted.
 * 4. Veldt itself must not be in the foreground — the pill exists for when the user has left
 *    the app, not to duplicate now-playing UI that is already on screen.
 * 5. Something must be [PillVisibilityInputs.loaded]. There is nothing to show otherwise.
 * 6. The music must be worth showing right now: either it is
 *    [PillVisibilityInputs.playing], or it was paused within [PillVisibilityInputs.hideDelayMs]
 *    of [PillVisibilityInputs.nowMs]. That window is a **half-open** one — elapsed time
 *    strictly less than the delay keeps the pill up; elapsed time equal to or past the delay
 *    hides it — so this pure check agrees with the moment [IslandStateMachine]'s own
 *    auto-hide timer actually fires (it delays exactly [PillVisibilityInputs.hideDelayMs]
 *    then hides, rather than one tick later).
 */
object PillVisibility {
    fun decide(inputs: PillVisibilityInputs): Boolean {
        if (inputs.mode != PillMode.BUILT_IN) return false
        if (inputs.wispInstalled && !inputs.forceBuiltIn) return false
        if (!inputs.overlayGranted) return false
        if (inputs.appInForeground) return false
        if (!inputs.loaded) return false
        if (inputs.playing) return true
        val pausedAt = inputs.pausedAtMs ?: return false
        return inputs.nowMs - pausedAt < inputs.hideDelayMs
    }
}
