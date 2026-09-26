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

    /** A real tap: the click consumes on the UP's Main pass, then the gesture ends (Final pass). */
    @Test fun `down, click, gesture end - the click reads the down's state`() {
        val latch = FadedTapLatch()
        latch.down(faded = true)
        assertTrue(latch.consume(fadedNow = false))
        latch.gestureEnded()
        assertFalse("nothing is left for a later non-pointer activation", latch.consume(fadedNow = false))
    }

    /** The review case: a partial drag cancels the press, so no click consumes the latch. The
     *  gesture's end must clear it, and a later non-pointer activation then uses the LIVE state —
     *  in both directions. */
    @Test fun `down, drag-cancel, gesture end - a later non-pointer activation uses the live state`() {
        val latch = FadedTapLatch()
        latch.down(faded = true) // faded at the drag's down...
        latch.gestureEnded() // ...drag cancelled the press; no click
        assertFalse("stale faded=true must not swallow a live act-tap", latch.consume(fadedNow = false))

        latch.down(faded = false)
        latch.gestureEnded()
        assertTrue("stale faded=false must not act on a faded screen", latch.consume(fadedNow = true))
    }

    @Test fun `a newer down replaces an older one`() {
        val latch = FadedTapLatch()
        latch.down(faded = true)
        latch.down(faded = false)
        assertFalse(latch.consume(fadedNow = true))
    }
}
