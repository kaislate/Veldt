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
}
