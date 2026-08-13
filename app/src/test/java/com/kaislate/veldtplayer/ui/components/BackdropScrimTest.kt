// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrim over the blurred cover, per theme.
 *
 * This exists because a contrast guarantee proved on the palette says nothing about the colour as
 * COMPOSITED. `ArtSeed.colors(isLight)` solves `onBg` for 4.5:1 against `bg`, but the now-playing
 * backdrop draws the blurred artwork with `bg` over it — so the ground the text actually lands on
 * is a blend, and on a light theme a saturated cover dragged it to luminance 0.49 while the solve
 * had assumed 0.93. Measured contrasts were 3.07 / 2.12 / 2.46:1.
 *
 * These assertions cannot prove legibility — only a measurement of a real frame can, and that was
 * done on a device. What they pin is the DECISION: that the two themes get different scrims and
 * that the light one is heavier. A collapse to a single branch is the regression that would
 * silently bring the defect back.
 */
class BackdropScrimTest {

    @Test
    fun `light and dark get different scrims, and light is the heavier one`() {
        val light = backdropScrim(isLight = true)
        val dark = backdropScrim(isLight = false)
        // Asserted as a pair: a collapse to one branch makes the failure message name it.
        assertEquals(
            "light must differ from dark at BOTH ends of the gradient",
            listOf(false, false),
            listOf(light.top == dark.top, light.bottom == dark.bottom),
        )
        assertTrue("light top must be heavier: ${light.top} vs ${dark.top}", light.top > dark.top)
        assertTrue(
            "light bottom must be heavier: ${light.bottom} vs ${dark.bottom}",
            light.bottom > dark.bottom,
        )
    }

    @Test
    fun `the dark scrim is unchanged, so the signature look cannot regress`() {
        // Literals on purpose. Dark is the app's identity; if these move it must be deliberate.
        assertEquals(BackdropScrim(0.35f, 0.80f), backdropScrim(isLight = false))
    }

    @Test
    fun `every scrim is bottom-weighted, because that is where the glyphs are`() {
        listOf(true, false).forEach { isLight ->
            val s = backdropScrim(isLight)
            assertTrue("bottom must exceed top for isLight=$isLight: $s", s.bottom > s.top)
        }
    }

    @Test
    fun `no scrim is fully opaque — the cover must remain the subject of the screen`() {
        listOf(true, false).forEach { isLight ->
            val s = backdropScrim(isLight)
            assertTrue("scrim must not erase the artwork for isLight=$isLight: $s", s.bottom < 1.0f)
        }
    }
}
