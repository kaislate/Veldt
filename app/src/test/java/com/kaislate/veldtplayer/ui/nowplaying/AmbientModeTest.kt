// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ambient mode hides controls on a timer, so every clause of its gate is a way it could
 * strand somebody rather than a preference. Pinned here because the failure mode is silent:
 * dropping a condition still compiles, still looks right on a sighted developer's device
 * with a track playing, and only bites the user who cannot see the screen.
 */
class AmbientModeTest {

    /** Everything a record playing, untouched, on a normal device looks like. */
    private fun eligible(
        reduced: Boolean = false,
        touchExploration: Boolean = false,
        isActive: Boolean = true,
        isPlaying: Boolean = true,
        sheetOpen: Boolean = false,
    ) = ambientEligible(reduced, touchExploration, isActive, isPlaying, sheetOpen)

    @Test fun `a track playing on an untouched surface is what ambient mode is for`() {
        assertTrue(eligible())
    }

    @Test fun `reduce animations turns it off entirely, not just its fade`() {
        assertFalse(eligible(reduced = true))
    }

    @Test fun `touch exploration turns it off — an idle reader is not an idle watcher`() {
        assertFalse(eligible(touchExploration = true))
    }

    @Test fun `nothing playing keeps the chrome, or the only way back would fade out`() {
        assertFalse(eligible(isActive = false))
    }

    @Test fun `paused keeps the chrome, so resuming never costs a wake-up tap first`() {
        assertFalse(eligible(isPlaying = false))
        // isStalled implies IDLE implies not playing, so the stalled surface is covered by
        // the same clause and never fades its already-dead transport away.
        assertFalse(eligible(isActive = true, isPlaying = false))
    }

    @Test fun `the queue sheet takes the touches, so the timer must not run behind it`() {
        assertFalse(eligible(sheetOpen = true))
    }

    @Test fun `any one disqualifier is enough on its own`() {
        assertFalse(eligible(reduced = true, isPlaying = true, isActive = true))
        assertFalse(eligible(touchExploration = true, sheetOpen = false))
    }

    // ---- the withdrawal, which is a separate question from the fade ----------------------
    //
    // ambientEligible decides whether the chrome may FADE. chromeReachable decides whether the
    // faded chrome may also be taken out of the accessibility and focus trees. Only the second
    // one can leave a user with no control to reach, so it has the stricter rule — and pinning
    // them apart is the point: an enabled service must NOT switch the fade off (that would
    // spend the app's signature on the users it is protecting), only the withdrawal.

    @Test fun `visible chrome is reachable, service or no service`() {
        assertTrue(chromeReachable(chromeLive = true, accessibilityActive = false))
        assertTrue(chromeReachable(chromeLive = true, accessibilityActive = true))
    }

    @Test fun `faded chrome is withdrawn only when nothing is listening`() {
        assertFalse(chromeReachable(chromeLive = false, accessibilityActive = false))
    }

    @Test fun `an enabled service keeps every control in both trees through the fade`() {
        // The M-4 case: Switch Access and Voice Access never produce the pointer down that
        // wakes ambient mode, so a withdrawal here is permanent for them.
        assertTrue(chromeReachable(chromeLive = false, accessibilityActive = true))
    }

    @Test fun `an enabled service does not disqualify the fade itself`() {
        // chromeReachable is not a sixth clause of ambientEligible, and this is why: isEnabled
        // is true for ANY service, so gating eligibility on it would delete the ambient fade
        // for a user running only a magnifier. The fade still runs; only the withdrawal stops.
        assertTrue(eligible())
        assertTrue(chromeReachable(chromeLive = false, accessibilityActive = true))
    }
}
