// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specification of [PillVisibility.decide], asserted directly against the boolean
 * formula in docs/superpowers/specs/2026-09-27-p1.5c-built-in-pill-design.md §3, as narrowed
 * by the controller's post-Task-1 ruling: this is an ELIGIBILITY gate only —
 * `mode = BUILT_IN ∧ (¬wispInstalled ∨ forceBuiltIn) ∧ overlayGranted ∧ ¬appInForeground`.
 * Media state and the hide delay are not inputs here; they stay with the ported
 * `IslandRules`/`IslandStateMachine`.
 *
 * [total] is the TOTAL table: every gate flipped in isolation against an otherwise-eligible
 * baseline, every mode value, and the wisp/override combination. The smaller directed tests
 * above it exist so a failure names the exact gate at a glance; [total] exists so no
 * combination was missed.
 */
class PillVisibilityTest {

    /** Every gate open: the only shape of input that is eligible. */
    private val eligible = PillVisibilityInputs(
        mode = PillMode.BUILT_IN,
        forceBuiltIn = false,
        wispInstalled = false,
        overlayGranted = true,
        appInForeground = false,
    )

    // ---- the baseline ----

    @Test fun `every gate open is eligible`() {
        assertTrue(PillVisibility.decide(eligible))
    }

    // ---- mode ----

    @Test fun `use-wisp mode is never eligible, even with every other gate open`() {
        assertFalse(PillVisibility.decide(eligible.copy(mode = PillMode.USE_WISP)))
    }

    @Test fun `off mode is never eligible, even with every other gate open`() {
        assertFalse(PillVisibility.decide(eligible.copy(mode = PillMode.OFF)))
    }

    // ---- Wisp deference and the override ----

    @Test fun `wisp installed makes the built-in pill ineligible`() {
        assertFalse(PillVisibility.decide(eligible.copy(wispInstalled = true)))
    }

    @Test fun `forcing built-in overrides deference to an installed Wisp`() {
        assertTrue(PillVisibility.decide(eligible.copy(wispInstalled = true, forceBuiltIn = true)))
    }

    @Test fun `the override does nothing when Wisp is not installed`() {
        assertTrue(PillVisibility.decide(eligible.copy(wispInstalled = false, forceBuiltIn = true)))
    }

    // ---- overlay permission ----

    @Test fun `no overlay permission is never eligible`() {
        assertFalse(PillVisibility.decide(eligible.copy(overlayGranted = false)))
    }

    // ---- app foreground ----

    @Test fun `veldt itself in the foreground is never eligible`() {
        assertFalse(PillVisibility.decide(eligible.copy(appInForeground = true)))
    }

    // ---- the TOTAL table ----

    /** One row of the table: a label, the inputs, and what [PillVisibility.decide] must return. */
    private data class Case(val label: String, val inputs: PillVisibilityInputs, val expected: Boolean)

    @Test fun total() {
        val cases = buildList {
            // mode: only BUILT_IN can ever be eligible.
            add(Case("mode BUILT_IN is eligible", eligible, true))
            add(Case("mode USE_WISP is never eligible", eligible.copy(mode = PillMode.USE_WISP), false))
            add(Case("mode OFF is never eligible", eligible.copy(mode = PillMode.OFF), false))

            // forceBuiltIn x wispInstalled: a 2x2, all four cells.
            add(Case("no Wisp, no override -> eligible", eligible.copy(wispInstalled = false, forceBuiltIn = false), true))
            add(Case("no Wisp, override -> eligible (override is a no-op)", eligible.copy(wispInstalled = false, forceBuiltIn = true), true))
            add(Case("Wisp installed, no override -> defers", eligible.copy(wispInstalled = true, forceBuiltIn = false), false))
            add(Case("Wisp installed, override -> eligible", eligible.copy(wispInstalled = true, forceBuiltIn = true), true))

            // overlayGranted.
            add(Case("overlay granted -> eligible", eligible.copy(overlayGranted = true), true))
            add(Case("overlay not granted -> never eligible", eligible.copy(overlayGranted = false), false))

            // appInForeground.
            add(Case("Veldt backgrounded -> eligible", eligible.copy(appInForeground = false), true))
            add(Case("Veldt foregrounded -> never eligible", eligible.copy(appInForeground = true), false))
        }

        for (case in cases) {
            assertEquals(case.label, case.expected, PillVisibility.decide(case.inputs))
        }
    }
}
