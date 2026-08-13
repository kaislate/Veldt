// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM. Whole-branch review FINDING 1: the wisptrail scrub-bar wave (the pinned production
 * default — see [WaveScrubBar]) drew every filament with additive [BlendMode.Plus] and a colour
 * lerped toward white, regardless of theme. Against a light-theme ground (bg tone ~98, near
 * white) that clips to white and the wave disappears — a defect [ArtSeedTest]'s palette-level
 * contrast checks cannot see, because it is introduced at draw time, not by the palette itself.
 *
 * [waveColorMode] is the extracted decision: this test asserts the decision, not "visible",
 * which cannot be asserted from a JVM unit test. Asserted as a PAIR so a collapse to one branch
 * (e.g. both themes accidentally landing on the dark-theme tuple) fails with a message naming
 * which theme lost its distinct behaviour.
 */
class WaveStylesColorModeTest {

    @Test fun `dark and light themes pick opposite tint and blend branches`() {
        val dark = waveColorMode(isLight = false)
        val light = waveColorMode(isLight = true)

        assertEquals(
            "dark theme must keep the original lighten-toward-white, additive-glow look",
            WaveColorMode(tint = Color.White, blendMode = BlendMode.Plus),
            dark,
        )
        assertEquals(
            "light theme must darken toward black and composite normally, or the wave " +
                "clips to invisible against a near-white ground (FINDING 1)",
            WaveColorMode(tint = Color.Black, blendMode = BlendMode.SrcOver),
            light,
        )
    }
}
