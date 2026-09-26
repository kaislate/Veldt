// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM. The faded-chrome tap decision — see [tapOnlyWakes] for the device-measured race. */
class FadedTapTest {

    /** TOTAL over the inputs: the down decides whenever there was one. */
    @Test fun `the down's state decides, the click-time state only without a down`() {
        val table = mapOf(
            // The race: faded at down, already live again by the click ⇒ still only wakes.
            (true to false) to true,
            (true to true) to true,
            // Live at down: acts, even if something faded it by the click.
            (false to false) to false,
            (false to true) to false,
        )
        table.forEach { (input, expected) ->
            assertEquals("fadedAtDown=${input.first} fadedNow=${input.second}", expected, tapOnlyWakes(input.first, input.second))
        }
        // No pointer down (key press / accessibility action): the current state.
        assertTrue(tapOnlyWakes(null, fadedNow = true))
        assertFalse(tapOnlyWakes(null, fadedNow = false))
    }

    @Test fun `the latch answers from the down, then forgets it`() {
        val latch = FadedTapLatch()
        latch.down(faded = true)
        assertTrue("the woken chrome at click time must not turn the tap into an action", latch.consume(fadedNow = false))
        assertFalse("a later non-pointer click must not read the stale latch", latch.consume(fadedNow = false))
    }

    @Test fun `a newer down replaces an older one`() {
        val latch = FadedTapLatch()
        latch.down(faded = true)
        latch.down(faded = false)
        assertFalse(latch.consume(fadedNow = true))
    }
}
