// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The specification of [PillVisibility.decide], asserted directly against the boolean
 * formula in docs/superpowers/specs/2026-09-27-p1.5c-built-in-pill-design.md §3:
 *
 * `mode = BUILT_IN ∧ (¬wispInstalled ∨ forceBuiltIn) ∧ overlayGranted ∧ ¬appInForeground ∧
 * loaded ∧ (playing ∨ paused within hideDelay)`
 *
 * [total] is the TOTAL table: every gate flipped in isolation against an otherwise-showing
 * baseline, every mode value, the wisp/override combination, every media-state combination,
 * and the hide-delay boundary from both sides. The smaller directed tests above it exist so
 * a failure names the exact gate at a glance; [total] exists so no combination was missed.
 */
class PillVisibilityTest {

    /** Every gate open, music actively playing: the only shape of input that shows the pill. */
    private val showing = PillVisibilityInputs(
        mode = PillMode.BUILT_IN,
        forceBuiltIn = false,
        wispInstalled = false,
        overlayGranted = true,
        appInForeground = false,
        loaded = true,
        playing = true,
        pausedAtMs = null,
        nowMs = 0L,
        hideDelayMs = 25_000L,
    )

    // ---- the baseline ----

    @Test fun `every gate open and playing shows the pill`() {
        assertTrue(PillVisibility.decide(showing))
    }

    // ---- mode ----

    @Test fun `use-wisp mode never shows, even with every other gate open`() {
        assertFalse(PillVisibility.decide(showing.copy(mode = PillMode.USE_WISP)))
    }

    @Test fun `off mode never shows, even with every other gate open`() {
        assertFalse(PillVisibility.decide(showing.copy(mode = PillMode.OFF)))
    }

    // ---- Wisp deference and the override ----

    @Test fun `wisp installed stands the built-in pill down`() {
        assertFalse(PillVisibility.decide(showing.copy(wispInstalled = true)))
    }

    @Test fun `forcing built-in overrides deference to an installed Wisp`() {
        assertTrue(PillVisibility.decide(showing.copy(wispInstalled = true, forceBuiltIn = true)))
    }

    @Test fun `the override does nothing when Wisp is not installed`() {
        assertTrue(PillVisibility.decide(showing.copy(wispInstalled = false, forceBuiltIn = true)))
    }

    // ---- overlay permission ----

    @Test fun `no overlay permission never shows`() {
        assertFalse(PillVisibility.decide(showing.copy(overlayGranted = false)))
    }

    // ---- app foreground ----

    @Test fun `veldt itself in the foreground never shows`() {
        assertFalse(PillVisibility.decide(showing.copy(appInForeground = true)))
    }

    // ---- media state ----

    @Test fun `nothing loaded never shows, playing flag notwithstanding`() {
        assertFalse(PillVisibility.decide(showing.copy(loaded = false)))
    }

    @Test fun `loaded but neither playing nor ever paused does not show`() {
        assertFalse(PillVisibility.decide(showing.copy(playing = false, pausedAtMs = null)))
    }

    @Test fun `paused within the hide delay still shows`() {
        assertTrue(
            PillVisibility.decide(
                showing.copy(playing = false, pausedAtMs = 1_000L, nowMs = 1_000L + 24_999L),
            ),
        )
    }

    @Test fun `paused past the hide delay does not show`() {
        assertFalse(
            PillVisibility.decide(
                showing.copy(playing = false, pausedAtMs = 1_000L, nowMs = 1_000L + 25_001L),
            ),
        )
    }

    // ---- the hide-delay boundary, from both sides ----

    @Test fun `one millisecond short of the hide delay still shows`() {
        assertTrue(
            PillVisibility.decide(
                showing.copy(
                    playing = false,
                    pausedAtMs = 0L,
                    nowMs = 24_999L,
                    hideDelayMs = 25_000L,
                ),
            ),
        )
    }

    @Test fun `exactly the hide delay does not show`() {
        // Half-open on purpose: IslandStateMachine's own auto-hide timer delays exactly
        // hideDelayMs then hides, so the pure check must agree at that instant rather than
        // one tick later.
        assertFalse(
            PillVisibility.decide(
                showing.copy(
                    playing = false,
                    pausedAtMs = 0L,
                    nowMs = 25_000L,
                    hideDelayMs = 25_000L,
                ),
            ),
        )
    }

    @Test fun `one millisecond past the hide delay does not show`() {
        assertFalse(
            PillVisibility.decide(
                showing.copy(
                    playing = false,
                    pausedAtMs = 0L,
                    nowMs = 25_001L,
                    hideDelayMs = 25_000L,
                ),
            ),
        )
    }

    @Test fun `zero elapsed time since the pause still shows`() {
        assertTrue(
            PillVisibility.decide(
                showing.copy(playing = false, pausedAtMs = 5_000L, nowMs = 5_000L, hideDelayMs = 25_000L),
            ),
        )
    }

    // ---- the TOTAL table ----

    /** One row of the table: a label, the inputs, and what [PillVisibility.decide] must return. */
    private data class Case(val label: String, val inputs: PillVisibilityInputs, val expected: Boolean)

    @Test fun total() {
        val cases = buildList {
            // mode: only BUILT_IN can ever show.
            add(Case("mode BUILT_IN shows", showing, true))
            add(Case("mode USE_WISP never shows", showing.copy(mode = PillMode.USE_WISP), false))
            add(Case("mode OFF never shows", showing.copy(mode = PillMode.OFF), false))

            // forceBuiltIn x wispInstalled: a 2x2, all four cells.
            add(Case("no Wisp, no override -> shows", showing.copy(wispInstalled = false, forceBuiltIn = false), true))
            add(Case("no Wisp, override -> shows (override is a no-op)", showing.copy(wispInstalled = false, forceBuiltIn = true), true))
            add(Case("Wisp installed, no override -> defers", showing.copy(wispInstalled = true, forceBuiltIn = false), false))
            add(Case("Wisp installed, override -> shows", showing.copy(wispInstalled = true, forceBuiltIn = true), true))

            // overlayGranted.
            add(Case("overlay granted -> shows", showing.copy(overlayGranted = true), true))
            add(Case("overlay not granted -> never shows", showing.copy(overlayGranted = false), false))

            // appInForeground.
            add(Case("Veldt backgrounded -> shows", showing.copy(appInForeground = false), true))
            add(Case("Veldt foregrounded -> never shows", showing.copy(appInForeground = true), false))

            // media state: loaded x playing x paused-recency, every combination that matters.
            add(Case("not loaded, not playing -> never shows", showing.copy(loaded = false, playing = false, pausedAtMs = null), false))
            add(Case("not loaded, playing (contradictory input) -> never shows", showing.copy(loaded = false, playing = true), false))
            add(Case("loaded and playing -> shows", showing.copy(loaded = true, playing = true), true))
            add(Case("loaded, not playing, never paused -> never shows", showing.copy(loaded = true, playing = false, pausedAtMs = null), false))
            add(
                Case(
                    "loaded, not playing, paused within delay -> shows",
                    showing.copy(loaded = true, playing = false, pausedAtMs = 0L, nowMs = 100L, hideDelayMs = 1_000L),
                    true,
                ),
            )
            add(
                Case(
                    "loaded, not playing, paused past delay -> never shows",
                    showing.copy(loaded = true, playing = false, pausedAtMs = 0L, nowMs = 2_000L, hideDelayMs = 1_000L),
                    false,
                ),
            )

            // hide-delay boundary, both sides, at the exact millisecond.
            add(
                Case(
                    "boundary - 1ms -> shows",
                    showing.copy(loaded = true, playing = false, pausedAtMs = 0L, nowMs = 999L, hideDelayMs = 1_000L),
                    true,
                ),
            )
            add(
                Case(
                    "boundary exactly -> never shows",
                    showing.copy(loaded = true, playing = false, pausedAtMs = 0L, nowMs = 1_000L, hideDelayMs = 1_000L),
                    false,
                ),
            )
            add(
                Case(
                    "boundary + 1ms -> never shows",
                    showing.copy(loaded = true, playing = false, pausedAtMs = 0L, nowMs = 1_001L, hideDelayMs = 1_000L),
                    false,
                ),
            )
        }

        for (case in cases) {
            assertEquals(case.label, case.expected, PillVisibility.decide(case.inputs))
        }
    }
}
