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
 * pass by either velocity or position would not say which rule it pinned.
 */
class PlayerSheetSettleTest {

    private companion object {
        const val HEIGHT = 2_000f
        const val FLING = 1_000f
        val THRESHOLD = HEIGHT * SHEET_COLLAPSE_POSITION
    }

    private fun settle(offset: Float, velocity: Float, height: Float = HEIGHT) =
        sheetSettleTarget(offset = offset, velocity = velocity, height = height, flingVelocity = FLING)

    // ------------------------------------------------------------------------------ velocity

    /** A short, quick flick down near the top still puts the player away: direction wins. */
    @Test fun `a downward fling collapses from anywhere, even just below the top`() {
        assertEquals(SheetValue.Collapsed, settle(offset = 40f, velocity = FLING))
        assertEquals(SheetValue.Collapsed, settle(offset = 40f, velocity = FLING * 5))
    }

    /** The peek-and-flick-back: pulled most of the way down, then thrown up, it comes back. */
    @Test fun `an upward fling expands from anywhere, even near the bottom`() {
        assertEquals(SheetValue.Expanded, settle(offset = HEIGHT * 0.9f, velocity = -FLING))
        assertEquals(SheetValue.Expanded, settle(offset = HEIGHT * 0.9f, velocity = -FLING * 5))
    }

    // ------------------------------------------------------------------------------ position

    /** A peek released slowly above the threshold springs back — the "look behind" gesture. */
    @Test fun `a slow release above the threshold springs back`() {
        assertEquals(SheetValue.Expanded, settle(offset = THRESHOLD - 1f, velocity = 0f))
        assertEquals(SheetValue.Expanded, settle(offset = THRESHOLD - 1f, velocity = FLING - 1f))
        assertEquals(SheetValue.Expanded, settle(offset = 0f, velocity = 0f))
    }

    /** Let go slowly near the bottom, and it carries on to the mini-player. */
    @Test fun `a slow release past the threshold collapses`() {
        assertEquals(SheetValue.Collapsed, settle(offset = THRESHOLD, velocity = 0f))
        assertEquals(SheetValue.Collapsed, settle(offset = THRESHOLD + 1f, velocity = -(FLING - 1f)))
        assertEquals(SheetValue.Collapsed, settle(offset = HEIGHT, velocity = 0f))
    }

    /**
     * Pinned to the spec's band so a retune that drifts outside "~35-40%" is a visible edit here
     * rather than a silent change to how every release feels.
     */
    @Test fun `the collapse threshold sits in the spec's 35 to 40 percent band`() {
        assertTrue("was $SHEET_COLLAPSE_POSITION", SHEET_COLLAPSE_POSITION in 0.35f..0.40f)
        assertEquals(400f, SHEET_FLING_DP_PER_S)
    }

    // ------------------------------------------------------------------------------ guard

    /** An unmeasured sheet has no travel; deciding on offset / 0 would decide on NaN. */
    @Test fun `zero or negative height settles collapsed whatever the release`() {
        assertEquals(SheetValue.Collapsed, settle(offset = 0f, velocity = 0f, height = 0f))
        assertEquals(SheetValue.Collapsed, settle(offset = 0f, velocity = -FLING * 10, height = 0f))
        assertEquals(SheetValue.Collapsed, settle(offset = 10f, velocity = 0f, height = -5f))
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
