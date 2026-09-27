// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
//
// New for Veldt (P1.5c): the built-in pill's ELIGIBILITY decision, per
// docs/superpowers/specs/2026-09-27-p1.5c-built-in-pill-design.md §3. Not a port of any
// single Veldt Wisp file — it folds together the mode/Wisp-deference/permission/foreground
// gates that have no equivalent in Wisp (which has no "defer to a sibling app" concept).
//
// Controller ruling (2026-09-27, after Task 1): this is an eligibility gate only. It does
// NOT decide moment-to-moment show/hide — media state (loaded/playing/paused) and the hide
// delay stay entirely with the ported `IslandRules`/`IslandStateMachine`, which already
// encode that (grace window, auto-hide timer, idempotence). Task 4 feeds this eligibility
// into the state machine's `enabled` gate and drives it from the bus for everything else.

package com.kaislate.veldtplayer.pill

/**
 * The user's three-way choice for the built-in pill.
 *
 * Only [BUILT_IN] can ever show anything; [USE_WISP] and [OFF] both mean "Veldt draws
 * nothing", they differ only in what the settings screen tells the user about why.
 */
enum class PillMode { BUILT_IN, USE_WISP, OFF }

/**
 * Everything [PillVisibility.decide] needs to answer "is the built-in pill eligible to be
 * on screen at all right now", gathered into one value so the decision is a single total
 * function of its inputs.
 *
 * Deliberately excludes media state (is something loaded, playing, how long has it been
 * paused) and the hide delay: those are runtime, moment-to-moment concerns owned by
 * [com.kaislate.veldtplayer.pill.overlay.IslandStateMachine] (its `enabled` gate is fed
 * from this eligibility decision; everything else is that class's business, not this one's).
 *
 * @param mode the user's setting.
 * @param forceBuiltIn the "use built-in anyway" override; only meaningful when [wispInstalled].
 * @param wispInstalled whether `com.kaislate.veldt` (Veldt Wisp) is installed on this device.
 * @param overlayGranted `Settings.canDrawOverlays` for Veldt.
 * @param appInForeground Veldt itself is the app on screen — showing the pill over it would
 *   cover the very UI it summarises.
 */
data class PillVisibilityInputs(
    val mode: PillMode,
    val forceBuiltIn: Boolean = false,
    val wispInstalled: Boolean = false,
    val overlayGranted: Boolean = false,
    val appInForeground: Boolean = false,
)

/**
 * The pure, total decision of whether the built-in pill is *eligible* to show at all right
 * now — not whether it is showing this instant, which is
 * [com.kaislate.veldtplayer.pill.overlay.IslandStateMachine]'s job once this gate is open.
 *
 * Every gate is a conjunction — a single closed gate makes the pill ineligible regardless of
 * what any other input says. In order:
 *
 * 1. [PillVisibilityInputs.mode] must be [PillMode.BUILT_IN]. [PillMode.USE_WISP] and
 *    [PillMode.OFF] are never eligible, unconditionally.
 * 2. If Veldt Wisp is installed, the built-in pill stands down ([PillVisibilityInputs.wispInstalled])
 *    *unless* the user has explicitly overridden that with [PillVisibilityInputs.forceBuiltIn] —
 *    the two apps would otherwise both draw a pill for the same playback.
 * 3. The overlay permission must actually be granted.
 * 4. Veldt itself must not be in the foreground — the pill exists for when the user has left
 *    the app, not to duplicate now-playing UI that is already on screen.
 */
object PillVisibility {
    fun decide(inputs: PillVisibilityInputs): Boolean {
        if (inputs.mode != PillMode.BUILT_IN) return false
        if (inputs.wispInstalled && !inputs.forceBuiltIn) return false
        if (!inputs.overlayGranted) return false
        if (inputs.appInForeground) return false
        return true
    }
}
