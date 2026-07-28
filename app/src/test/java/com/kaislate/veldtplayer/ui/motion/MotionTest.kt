// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionTest {

    @Test fun `reduced motion only when animator scale is exactly zero`() {
        assertTrue(Motion.reduced(0f))
        assertFalse(Motion.reduced(1f))
        assertFalse(Motion.reduced(0.5f))
        assertFalse(Motion.reduced(2f))
    }

    @Test fun `stagger delay grows with index`() {
        assertEquals(0, Motion.staggerDelayMs(0, reduced = false))
        assertEquals(Motion.STAGGER_MS, Motion.staggerDelayMs(1, reduced = false))
        assertEquals(3 * Motion.STAGGER_MS, Motion.staggerDelayMs(3, reduced = false))
    }

    /**
     * Pinned to a literal on purpose: every other assertion here is written in terms of
     * STAGGER_MS, so zeroing it would delete the stagger and leave the suite green.
     */
    @Test fun `stagger step is a real, non-zero delay`() {
        assertEquals(28, Motion.staggerDelayMs(1, reduced = false))
    }

    @Test fun `stagger delay is capped so long lists do not crawl`() {
        val capped = Motion.STAGGER_CAP * Motion.STAGGER_MS
        assertEquals(capped, Motion.staggerDelayMs(Motion.STAGGER_CAP, reduced = false))
        assertEquals(capped, Motion.staggerDelayMs(500, reduced = false))
    }

    @Test fun `reduced motion collapses stagger to zero`() {
        assertEquals(0, Motion.staggerDelayMs(0, reduced = true))
        assertEquals(0, Motion.staggerDelayMs(7, reduced = true))
        assertEquals(0, Motion.staggerDelayMs(500, reduced = true))
    }

    @Test fun `negative index is treated as first item`() {
        assertEquals(0, Motion.staggerDelayMs(-3, reduced = false))
    }
}
