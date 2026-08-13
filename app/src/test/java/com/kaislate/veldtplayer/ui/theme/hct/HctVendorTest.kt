// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme.hct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boundary test for vendored code. A bad copy shows up here as a failed round trip rather
 * than three tasks later as "the colours look odd", which is unattributable.
 *
 * Pure JVM — no Robolectric. That these classes need no Android runtime is the property that
 * makes vendoring them safe, so the absence of a runner here is itself part of the assertion.
 */
class HctVendorTest {

    private val corpus = listOf(
        0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
        0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF808080.toInt(),
        0xFF4A6FA5.toInt(), 0xFF8A8A93.toInt(),
    )

    @Test fun `argb survives a round trip through HCT`() {
        // Named per colour, so a failure says WHICH colour broke rather than "one of eight".
        val broken = corpus.filter { Hct.fromInt(it).toInt() != it }
        assertEquals("colours that failed to round-trip: ${broken.map { Integer.toHexString(it) }}",
            emptyList<Int>(), broken)
    }

    @Test fun `tone maps monotonically from black to white`() {
        val p = TonalPalette.fromHueAndChroma(250.0, 40.0)
        val tones = listOf(0, 10, 40, 60, 90, 100).map { Hct.fromInt(p.tone(it)).tone }
        assertEquals(tones.sorted(), tones)
        assertTrue("tone(0) should be near-black", tones.first() < 5.0)
        assertTrue("tone(100) should be near-white", tones.last() > 95.0)
    }

    @Test fun `contrast solvers return a tone that actually achieves the ratio`() {
        val lighter = Contrast.lighter(10.0, Contrast.RATIO_45)
        val darker = Contrast.darker(98.0, Contrast.RATIO_45)
        assertTrue("lighter(10, 4.5) unreachable", lighter > 0)
        assertTrue("darker(98, 4.5) unreachable", darker > 0)
        assertTrue(Contrast.ratioOfTones(lighter, 10.0) >= 4.5)
        assertTrue(Contrast.ratioOfTones(darker, 98.0) >= 4.5)
    }

    @Test fun `an unreachable ratio reports failure rather than lying`() {
        // Nothing is 21:1 against mid-grey. The solver must say so; the derivation clamps on it.
        assertTrue("expected a negative sentinel", Contrast.lighter(50.0, 21.0) < 0)
    }
}
