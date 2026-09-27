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
 * The last test is the TOTAL table: every gate flipped in isolation against the all-gates-open
 * baseline, every mode value, and the wisp/override combination. The smaller directed tests
 * above it exist so a failure names the exact gate at a glance; the table exists so no
 * combination was missed.
 *
 * The vocabulary is the class's own: [PillVisibilityInputs] are GATES, and "eligible" is only
 * ever said of what [PillVisibility.decide] returns, never of an input.
 */
class PillVisibilityTest {

    /** Every gate open: the only shape of input [PillVisibility.decide] calls eligible. */
    private val allGatesOpen = PillVisibilityInputs(
        mode = PillMode.BUILT_IN,
        forceBuiltIn = false,
        wispInstalled = false,
        overlayGranted = true,
        appInForeground = false,
    )

    // ---- the baseline ----

    @Test fun `every gate open is eligible`() {
        assertTrue(PillVisibility.decide(allGatesOpen))
    }

    // ---- mode ----

    @Test fun `off mode is never eligible, even with every other gate open`() {
        assertFalse(PillVisibility.decide(allGatesOpen.copy(mode = PillMode.OFF)))
    }

    // ---- the stored mode, including the retired USE_WISP (Step 5 §9) ----

    @Test fun `every stored mode string reads as the mode it should`() {
        val stored = listOf("BUILT_IN", "OFF", PillMode.LEGACY_USE_WISP, null, "NOT_A_MODE")
        assertEquals(
            listOf(PillMode.BUILT_IN, PillMode.OFF, PillMode.BUILT_IN, PillMode.BUILT_IN, PillMode.BUILT_IN),
            stored.map(PillMode::fromStored),
        )
    }

    /** The legacy value, from the stored string to the decision: on without Wisp, Wisp's with it. */
    @Test fun `a stored legacy USE_WISP is eligible without Wisp and stands down for it`() {
        val legacy = allGatesOpen.copy(mode = PillMode.fromStored(PillMode.LEGACY_USE_WISP))
        assertEquals(
            listOf(true, false, true),
            listOf(
                PillVisibility.decide(legacy),
                PillVisibility.decide(legacy.copy(wispInstalled = true)),
                PillVisibility.decide(legacy.copy(wispInstalled = true, forceBuiltIn = true)),
            ),
        )
    }

    // ---- Wisp deference and the override ----

    @Test fun `wisp installed makes the built-in pill ineligible`() {
        assertFalse(PillVisibility.decide(allGatesOpen.copy(wispInstalled = true)))
    }

    @Test fun `forcing built-in overrides deference to an installed Wisp`() {
        assertTrue(PillVisibility.decide(allGatesOpen.copy(wispInstalled = true, forceBuiltIn = true)))
    }

    @Test fun `the override does nothing when Wisp is not installed`() {
        assertTrue(PillVisibility.decide(allGatesOpen.copy(wispInstalled = false, forceBuiltIn = true)))
    }

    // ---- overlay permission ----

    @Test fun `no overlay permission is never eligible`() {
        assertFalse(PillVisibility.decide(allGatesOpen.copy(overlayGranted = false)))
    }

    // ---- app foreground ----

    @Test fun `veldt itself in the foreground is never eligible`() {
        assertFalse(PillVisibility.decide(allGatesOpen.copy(appInForeground = true)))
    }

    // ---- the TOTAL table ----

    /** One row of the table: a label, the inputs, and what [PillVisibility.decide] must return. */
    private data class Case(val label: String, val inputs: PillVisibilityInputs, val expected: Boolean)

    @Test fun `every row of the total table decides as the formula says`() {
        val cases = buildList {
            // mode: only BUILT_IN (the switch on) can ever be eligible. The retired USE_WISP is
            // not a mode any more; it reads as BUILT_IN before it gets here (tests above).
            add(Case("mode BUILT_IN is eligible", allGatesOpen, true))
            add(Case("mode OFF is never eligible", allGatesOpen.copy(mode = PillMode.OFF), false))

            // forceBuiltIn x wispInstalled: a 2x2, all four cells.
            add(Case("no Wisp, no override -> eligible", allGatesOpen.copy(wispInstalled = false, forceBuiltIn = false), true))
            add(Case("no Wisp, override -> eligible (override is a no-op)", allGatesOpen.copy(wispInstalled = false, forceBuiltIn = true), true))
            add(Case("Wisp installed, no override -> stands down",allGatesOpen.copy(wispInstalled = true, forceBuiltIn = false), false))
            add(Case("Wisp installed, override -> eligible", allGatesOpen.copy(wispInstalled = true, forceBuiltIn = true), true))

            // overlayGranted.
            add(Case("overlay granted -> eligible", allGatesOpen.copy(overlayGranted = true), true))
            add(Case("overlay not granted -> never eligible", allGatesOpen.copy(overlayGranted = false), false))

            // appInForeground.
            add(Case("Veldt backgrounded -> eligible", allGatesOpen.copy(appInForeground = false), true))
            add(Case("Veldt foregrounded -> never eligible", allGatesOpen.copy(appInForeground = true), false))
        }

        for (case in cases) {
            assertEquals(case.label, case.expected, PillVisibility.decide(case.inputs))
        }
    }
}
