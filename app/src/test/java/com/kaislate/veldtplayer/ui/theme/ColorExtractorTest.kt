// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class ColorExtractorTest {

    /** WCAG relative luminance — recomputed here independently of the implementation. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test fun `null bitmap yields the neutral fallback with no wave colours`() {
        val c = ColorExtractor.extract(null)
        assertTrue("fallback bg should be dark", luminance(c.bg) < 0.2)
        assertTrue("fallback onBg should be light", luminance(c.onBg) > 0.5)
        assertTrue("fallback has no wave colours", c.waveColors.isEmpty())
    }

    @Test fun `null bitmap fallback is stable across calls`() {
        assertEquals(ColorExtractor.extract(null), ColorExtractor.extract(null))
    }

    @Test fun `ensureContrast lifts a dark accent off a dark background to at least 3 to 1`() {
        val bg = Color(0xFF101014)
        val fg = Color(0xFF1A1D22) // nearly invisible against bg
        val out = ColorExtractor.ensureContrast(fg, bg)
        assertTrue("expected >= 3.0, got ${ratio(out, bg)}", ratio(out, bg) >= 3.0)
    }

    @Test fun `ensureContrast leaves an already-legible colour unchanged`() {
        val bg = Color(0xFF101014)
        val fg = Color(0xFFFFFFFF)
        assertEquals(fg, ColorExtractor.ensureContrast(fg, bg))
    }

    @Test fun `ensureContrast preserves alpha`() {
        val bg = Color(0xFF101014)
        val fg = Color(0x8021242A)
        assertEquals(0x80 / 255f, ColorExtractor.ensureContrast(fg, bg).alpha, 0.01f)
    }

    @Test fun `ensureContrast terminates on an impossible request`() {
        // White on white can never reach 3:1 — the bounded loop must still return.
        val white = Color(0xFFFFFFFF)
        val out = ColorExtractor.ensureContrast(white, white)
        assertTrue(out.red >= 0f && out.red <= 1f)
    }
}
