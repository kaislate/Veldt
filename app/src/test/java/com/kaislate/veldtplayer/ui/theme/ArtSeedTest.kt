// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.kaislate.veldtplayer.ui.theme.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM. The whole point of deriving tones rather than picking swatches is that legibility
 * is constructed, so these assert the CONSTRUCTION — a ratio, in both themes — rather than
 * particular colours, which are judgement and may be tuned.
 */
class ArtSeedTest {

    private fun seed(hue: Double, chroma: Double, waves: List<Chromaticity> = emptyList()) =
        ArtSeed(Chromaticity(hue, chroma), waves)

    /** WCAG AA: 4.5:1 body text, 3:1 large/non-text. Copied from the spec, not invented here. */
    private val AA_TEXT = 4.5
    private val AA_LARGE = 3.0

    private fun ratio(a: Color, b: Color): Double = ColorExtractor.contrastRatio(a, b)

    private val corpus = listOf(
        "black cover" to seed(0.0, 0.0),
        "saturated red" to seed(25.0, 84.0),
        "muted blue" to seed(250.0, 20.0),
        "bile yellow-green" to seed(90.0, 40.0),
        "near-grey" to seed(180.0, 2.0),
    )

    @Test fun `body text clears AA against the background in BOTH themes`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).mapNotNull { light ->
                val c = s.colors(isLight = light)
                val r = ratio(c.onBg, c.bg)
                if (r >= AA_TEXT) null else "$name ${if (light) "light" else "dark"} = %.2f".format(r)
            }
        }
        assertEquals("onBg failed AA in: $failures", emptyList<String>(), failures)
    }

    @Test fun `accent clears AA-large against the background in BOTH themes`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).mapNotNull { light ->
                val c = s.colors(isLight = light)
                val r = ratio(c.accent, c.bg)
                if (r >= AA_LARGE) null else "$name ${if (light) "light" else "dark"} = %.2f".format(r)
            }
        }
        assertEquals("accent failed AA-large in: $failures", emptyList<String>(), failures)
    }

    @Test fun `light and dark do not collapse — one seed, two grounds, both legible`() {
        val s = seed(250.0, 40.0)
        val light = s.colors(isLight = true)
        val dark = s.colors(isLight = false)
        // The PAIR, so the failure message is the collapse itself.
        assertNotEquals(light.bg to true, dark.bg to true)
        assertTrue("light bg should be light", light.bg.luminance() > dark.bg.luminance())
    }

    @Test fun `a monochrome seed invents no hue in either theme`() {
        val mono = seed(123.0, 0.0)     // hue present but chroma zero: hue is noise
        listOf(true, false).forEach { light ->
            val c = mono.colors(isLight = light)
            assertEquals("bg should be neutral grey in ${if (light) "light" else "dark"}",
                c.bg.red, c.bg.green, 0.02f)
            assertEquals(c.bg.green, c.bg.blue, 0.02f)
        }
    }

    @Test fun `every wave colour clears AA-large in BOTH themes`() {
        val s = seed(250.0, 40.0, listOf(Chromaticity(20.0, 60.0), Chromaticity(140.0, 50.0)))
        val failures = listOf(true, false).flatMap { light ->
            val c = s.colors(isLight = light)
            c.waveColors.filter { ratio(it, c.bg) < AA_LARGE }.map { "$light:$it" }
        }
        assertEquals("wave colours failed AA-large: $failures", emptyList<String>(), failures)
    }

    @Test fun `solve lands on an interior tone, not the unreachable-ratio clamp`() {
        // BG_TONE_LIGHT (98) is comfortably reachable by DARKENING — the correct light-theme
        // direction — landing on some interior tone well short of black. It is NOT reachable by
        // LIGHTENING, which a direction bug (control (a): always lighten) would fall back to,
        // and that fallback clamps straight to the extreme (tone 0). A ratio-only assertion
        // can't tell "the solver solved" apart from "the clamp fired", because tone 0 against a
        // tone-98 background clears AA comfortably too — so this checks the mechanism directly:
        // the solved tone must be interior, not the clamp's extreme.
        val s = seed(250.0, 40.0)
        val onTone = Hct.fromInt(s.colors(isLight = true).onBg.toArgb()).tone
        val accentTone = Hct.fromInt(s.colors(isLight = true).accent.toArgb()).tone
        assertTrue("onBg tone should be interior, not the clamp extreme: $onTone", onTone in 1.0..99.0)
        assertTrue("accent tone should be interior, not the clamp extreme: $accentTone", accentTone in 1.0..99.0)
    }

    @Test fun `the neutral seed is monochrome and derives per theme`() {
        assertTrue(ArtSeed.NEUTRAL.isMonochrome)
        // The live bug this prevents: a hardcoded dark neutral painted on a light screen.
        assertTrue("neutral must be LIGHT in light mode",
            ArtSeed.NEUTRAL.colors(isLight = true).bg.luminance() >
                ArtSeed.NEUTRAL.colors(isLight = false).bg.luminance())
    }
}
