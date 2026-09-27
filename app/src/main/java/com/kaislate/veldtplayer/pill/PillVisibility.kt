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
 * The user's "Floating pill" switch: [BUILT_IN] is on, [OFF] is off.
 *
 * On means "a now-playing pill when you leave Veldt", whoever draws it. With Veldt Wisp
 * installed, Wisp draws it (unless the user overrides that with
 * [PillVisibilityInputs.forceBuiltIn]); without Wisp, Veldt does. So two values are enough.
 *
 * Step 5 §9 retired a third value, stored as [LEGACY_USE_WISP], which meant "Veldt draws
 * nothing, Wisp does". It is still on disk for anyone who picked it, so [fromStored] reads it as
 * [BUILT_IN]. With Wisp installed that behaves exactly as before. Without Wisp the user now gets
 * Veldt's pill where they used to get nothing, which is the one-switch meaning the owner chose.
 * It is mapped where the stored string is parsed, rather than kept as a constant that
 * [PillVisibility] and the Settings switch would each have to remember to treat as "on": a value
 * that must always be read as another value is one no code should be able to hold.
 */
enum class PillMode {
    BUILT_IN,
    OFF,
    ;

    companion object {
        /** The name the retired third value was stored under (see the class KDoc). */
        const val LEGACY_USE_WISP = "USE_WISP"

        /**
         * The stored `pill_mode` string as a mode. [LEGACY_USE_WISP] reads as [BUILT_IN], and so
         * do absent and unrecognised values, [BUILT_IN] being the documented default. The legacy
         * case is spelled out even so: it is a decision, not an accident of the default, and it
         * has to survive the default ever changing.
         */
        fun fromStored(stored: String?): PillMode = when (stored) {
            LEGACY_USE_WISP -> BUILT_IN
            else -> entries.firstOrNull { it.name == stored } ?: BUILT_IN
        }
    }
}

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
 * @param mode the user's "Floating pill" switch.
 * @param forceBuiltIn the "Use Veldt's own pill instead" override; only meaningful when
 *   [wispInstalled].
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
 * 1. [PillVisibilityInputs.mode] must be [PillMode.BUILT_IN]. [PillMode.OFF] is never
 *    eligible, unconditionally.
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
