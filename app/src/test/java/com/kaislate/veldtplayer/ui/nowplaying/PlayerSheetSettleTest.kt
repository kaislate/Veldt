// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [sheetSettleTarget] — the decision behind every release of the now-playing sheet — and the two
 * fade curves that turn the collapsing sheet into the mini-player.
 *
 * Heights and velocities are in plain pixels; [FLING] stands in for 400 dp/s at some density.
 * The numbers are chosen so each case is on the far side of exactly ONE rule: a test that could
 * pass by either velocity or distance would not say which rule it pinned.
 *
 * Every distance case is asserted from BOTH starting rests. The first version used one absolute
 * line and only tested pulling down from the player; the push up from the mini-player then needed
 * 62% of the screen (device fix round 1). [SWITCH] is the same distance either way.
 */
class PlayerSheetSettleTest {

    private companion object {
        const val HEIGHT = 2_000f
        const val FLING = 1_000f
        val SWITCH = HEIGHT * SHEET_SWITCH_DISTANCE
    }

    private fun settle(
        offset: Float,
        velocity: Float,
        from: SheetValue,
        height: Float = HEIGHT,
    ) = sheetSettleTarget(
        offset = offset,
        velocity = velocity,
        height = height,
        flingVelocity = FLING,
        from = from,
    )

    // ------------------------------------------------------------------------------ velocity

    /** A short, quick flick down near the top still puts the player away: direction wins. */
    @Test fun `a downward fling collapses from anywhere, whichever rest it started at`() {
        assertEquals(SheetValue.Collapsed, settle(40f, FLING, from = SheetValue.Expanded))
        assertEquals(SheetValue.Collapsed, settle(40f, FLING * 5, from = SheetValue.Expanded))
        // Pushed nearly all the way up from the mini-player, then flicked back down.
        assertEquals(SheetValue.Collapsed, settle(40f, FLING, from = SheetValue.Collapsed))
    }

    /** A short flick up on the mini-player opens it; the peek-and-flick-back comes back. */
    @Test fun `an upward fling expands from anywhere, whichever rest it started at`() {
        assertEquals(SheetValue.Expanded, settle(HEIGHT * 0.95f, -FLING, from = SheetValue.Collapsed))
        assertEquals(SheetValue.Expanded, settle(HEIGHT * 0.9f, -FLING, from = SheetValue.Expanded))
        assertEquals(SheetValue.Expanded, settle(HEIGHT * 0.9f, -FLING * 5, from = SheetValue.Expanded))
    }

    // ------------------------------------------------------------------------------ distance, down

    /** A peek released slowly short of the switch distance springs back — "look behind". */
    @Test fun `pulled down from the player and let go short of the switch distance, it returns`() {
        assertEquals(SheetValue.Expanded, settle(SWITCH - 1f, 0f, from = SheetValue.Expanded))
        assertEquals(SheetValue.Expanded, settle(SWITCH - 1f, FLING - 1f, from = SheetValue.Expanded))
        assertEquals(SheetValue.Expanded, settle(0f, 0f, from = SheetValue.Expanded))
    }

    /** Let go slowly past it, and it carries on to the mini-player. */
    @Test fun `pulled down from the player past the switch distance, it collapses`() {
        assertEquals(SheetValue.Collapsed, settle(SWITCH, 0f, from = SheetValue.Expanded))
        assertEquals(SheetValue.Collapsed, settle(SWITCH + 1f, -(FLING - 1f), from = SheetValue.Expanded))
        assertEquals(SheetValue.Collapsed, settle(HEIGHT, 0f, from = SheetValue.Expanded))
    }

    // ------------------------------------------------------------------------------ distance, up

    /**
     * The fix-round case: from the mini-player, a slow push of just over 30% opens the player.
     * Under the old absolute 38%-from-the-top line this position (69% of the travel down, i.e.
     * 31% up) collapsed back.
     */
    @Test fun `pushed up from the mini-player past the switch distance, it expands`() {
        assertEquals(SheetValue.Expanded, settle(HEIGHT - SWITCH - 1f, 0f, from = SheetValue.Collapsed))
        assertEquals(SheetValue.Expanded, settle(HEIGHT - SWITCH - 1f, FLING - 1f, from = SheetValue.Collapsed))
        assertEquals(SheetValue.Expanded, settle(0f, 0f, from = SheetValue.Collapsed))
    }

    @Test fun `pushed up from the mini-player and let go short of the switch distance, it returns`() {
        assertEquals(SheetValue.Collapsed, settle(HEIGHT - SWITCH + 1f, 0f, from = SheetValue.Collapsed))
        assertEquals(SheetValue.Collapsed, settle(HEIGHT - SWITCH + 1f, -(FLING - 1f), from = SheetValue.Collapsed))
        assertEquals(SheetValue.Collapsed, settle(HEIGHT, 0f, from = SheetValue.Collapsed))
    }

    /** The same position decides differently depending on where the drag began — which is the
     *  whole point, and what an absolute threshold cannot do. */
    @Test fun `the middle of the travel returns to whichever rest the drag started at`() {
        assertEquals(SheetValue.Expanded, settle(HEIGHT * 0.25f, 0f, from = SheetValue.Expanded))
        assertEquals(SheetValue.Expanded, settle(HEIGHT * 0.5f, 0f, from = SheetValue.Collapsed))
        assertEquals(SheetValue.Collapsed, settle(HEIGHT * 0.5f, 0f, from = SheetValue.Expanded))
        assertEquals(SheetValue.Collapsed, settle(HEIGHT * 0.75f, 0f, from = SheetValue.Collapsed))
    }

    /**
     * Pinned to the band the fix round asked for ("~30%") so a retune is a visible edit here
     * rather than a silent change to how every release feels.
     */
    @Test fun `the switch distance and fling speed are the specified values`() {
        assertTrue("was $SHEET_SWITCH_DISTANCE", SHEET_SWITCH_DISTANCE in 0.25f..0.35f)
        assertEquals(400f, SHEET_FLING_DP_PER_S)
    }

    // ------------------------------------------------------------------------------ guard

    /** An unmeasured sheet has no travel; deciding on offset / 0 would decide on NaN. */
    @Test fun `zero or negative height settles collapsed whatever the release`() {
        assertEquals(SheetValue.Collapsed, settle(0f, 0f, from = SheetValue.Expanded, height = 0f))
        assertEquals(SheetValue.Collapsed, settle(0f, -FLING * 10, from = SheetValue.Expanded, height = 0f))
        assertEquals(SheetValue.Collapsed, settle(10f, 0f, from = SheetValue.Collapsed, height = -5f))
    }

    // ------------------------------------------------------------------------------ fades

    /** The top half of the travel is the peek: the player is still entirely itself there. */
    @Test fun `player content is fully opaque through the peek and gone before the landing`() {
        assertEquals(1f, sheetContentAlpha(0f), 0f)
        assertEquals(1f, sheetContentAlpha(0.5f), 0f)
        assertEquals(0f, sheetContentAlpha(0.85f), 0f)
        assertEquals(0f, sheetContentAlpha(1f), 0f)
        val mid = sheetContentAlpha(0.7f)
        assertTrue("was $mid", mid > 0f && mid < 1f)
    }

    /** The mini-player row is invisible under an expanded sheet and fully back once landed. */
    @Test fun `mini-player fades in over the last part of the travel only`() {
        assertEquals(0f, miniPlayerAlpha(0f), 0f)
        assertEquals(0f, miniPlayerAlpha(0.7f), 0f)
        assertEquals(1f, miniPlayerAlpha(1f), 0f)
        val mid = miniPlayerAlpha(0.85f)
        assertTrue("was $mid", mid > 0f && mid < 1f)
    }

    /** The two curves cross: there is no stretch of travel where neither the player nor the row
     *  reads, which would flash the bare library at the moment of landing. */
    @Test fun `the player and the mini-player are never both invisible`() {
        var f = 0f
        while (f <= 1f) {
            assertTrue("both invisible at $f", sheetContentAlpha(f) + miniPlayerAlpha(f) > 0f)
            f += 0.01f
        }
    }
}
