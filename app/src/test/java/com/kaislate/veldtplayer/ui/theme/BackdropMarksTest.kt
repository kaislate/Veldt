// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.components.scrimAtText
import com.kaislate.veldtplayer.ui.theme.hct.TonalPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The non-text half of the same defect [BackdropTextTest] covers.
 *
 * Finding 14 fixed the title, artist and elapsed labels and left every MARK on that surface still
 * solved against `bg`. Measured on a device after that fix shipped, on the crimson probe:
 *
 * | mark | light | dark | drawn with |
 * |---|---|---|---|
 * | scrub track (unplayed) | 1.38 | 1.50 | `onBg` at `TRACK_ALPHA = 0.22` |
 * | shuffle / repeat OFF | 2.19 | 2.54 | `onBg` at `INACTIVE_ALPHA = 0.5` |
 * | repeat ON | 2.66 | 2.47 | `accent`, solved 3:1 against `bg` |
 * | wave body | 1.60 | 6.64 | `accent` |
 *
 * WCAG 1.4.11 wants 3:1 for all of them. These are the marks that carry a control's STATE, so
 * they are not decoration and not exempt — the toggles in particular have no other on/off signal.
 *
 * As in [BackdropTextTest], these assertions prove only that the SOLVE targets the real ground; a
 * rendered frame can still lose the guarantee downstream, which is exactly how the wave came to
 * measure 1.60 while its own colour was fine. Only a device measurement closes that, and the wave
 * has an additional renderer-side alpha that this corpus deliberately does not model.
 */
class BackdropMarksTest {

    private fun seed(hue: Double, chroma: Double, art: Color?) =
        ArtSeed(Chromaticity(hue, chroma), emptyList(), art)

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)

    /** Same corpus as [BackdropTextTest] — the extremes are the extremes for marks too. */
    private val corpus = listOf(
        "black cover" to seed(25.0, 84.0, Color(0xFF000000)),
        "white cover" to seed(25.0, 84.0, Color(0xFFFFFFFF)),
        "saturated red" to seed(25.0, 84.0, Color(0xFFD32F2F)),
        "greyscale cover, black mean" to seed(0.0, 0.0, Color(0xFF000000)),
        "greyscale cover, white mean" to seed(0.0, 0.0, Color(0xFFFFFFFF)),
        "no artwork" to seed(250.0, 40.0, null),
    )

    private fun groundOf(s: ArtSeed, bg: Color, alpha: Float): Color {
        val a = s.artMean ?: return bg
        fun mix(x: Float, y: Float) = x + (y - x) * alpha
        return Color(mix(a.red, bg.red), mix(a.green, bg.green), mix(a.blue, bg.blue))
    }

    @Test fun `both marks clear WCAG 1_4_11's 3 to 1 against the COMPOSITED ground, both themes`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).flatMap { light ->
                val alpha = scrimAtText(light)
                val bg = s.colors(light).bg
                val ground = groundOf(s, bg, alpha)
                val m = s.backdropMarks(bg, alpha, light)
                listOfNotNull(
                    ratio(m.accent, ground).let { if (it >= 3.0) null else "$name/$light/accent=%.2f".format(it) },
                    ratio(m.quiet, ground).let { if (it >= 3.0) null else "$name/$light/quiet=%.2f".format(it) },
                )
            }
        }
        assertEquals("marks failed against the composited ground: $failures", emptyList<String>(), failures)
    }

    /**
     * The on/off distinction must not rest on chroma alone (WCAG 1.4.1). Asserted as a LUMINANCE
     * gap rather than as inequality: two colours that differ only in hue are unequal and would
     * satisfy a `assertNotEquals`, while being exactly the failure this guards.
     */
    @Test fun `on and off states differ in LUMINANCE, not only in hue`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).mapNotNull { light ->
                val alpha = scrimAtText(light)
                val m = s.backdropMarks(s.colors(light).bg, alpha, light)
                val gap = ratio(m.accent, m.quiet)
                // 1.2:1 between the two states is modest by design — both are already solved
                // against the ground, so this guards the COLLAPSE case, not a second legibility
                // bar. A shared solve would land it at exactly 1.00.
                if (gap >= 1.2) null else "$name/$light/gap=%.2f".format(gap)
            }
        }
        assertEquals("on/off collapsed to one luminance: $failures", emptyList<String>(), failures)
    }

    /** The emphasised role is the more contrasting one, in both themes. */
    @Test fun `accent is the stronger of the two, never the weaker`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).mapNotNull { light ->
                val alpha = scrimAtText(light)
                val bg = s.colors(light).bg
                val ground = groundOf(s, bg, alpha)
                val m = s.backdropMarks(bg, alpha, light)
                val a = ratio(m.accent, ground)
                val q = ratio(m.quiet, ground)
                if (a >= q) null else "$name/$light/accent=%.2f < quiet=%.2f".format(a, q)
            }
        }
        assertEquals("the ON state was less visible than the OFF state: $failures", emptyList<String>(), failures)
    }

    /**
     * `artMean == null` is the no-artwork path — the ground IS `bg`, so the quiet mark must land on
     * exactly what [ArtSeed.colors] already solves for the same 3:1. The accent deliberately does
     * NOT match `colors().accent`: on the backdrop it carries 4.5:1 to hold the gap above `quiet`,
     * which is the whole point of the two-ratio design. Asserted rather than assumed, because a
     * regression here would silently change every art-less track's transport row.
     */
    @Test fun `no artwork puts the quiet mark exactly where colors() puts a 3 to 1 tint`() {
        val s = seed(250.0, 40.0, null)
        val bg = s.colors(true).bg
        val m = s.backdropMarks(bg, scrimAtText(true), isLight = true)
        val groundTone = 98.0 // BG_TONE_LIGHT — artMean == null, so the ground is bg itself
        val expected = Color(
            TonalPalette.fromHueAndChroma(250.0, ArtSeed.SURFACE_CHROMA)
                .tone(ArtSeed.solve(groundTone, 3.0, true).toInt())
        )
        assertEquals(expected, m.quiet)
    }

    /**
     * Monochrome seeds must stay monochrome. [ArtSeed.accentChroma] floors chroma at 32 so a washed
     * out cover still reads as an accent, but flooring a genuine ZERO would invent a hue nothing
     * supplied — and unlike `colors()`, this function is reached with `ArtSeed.NEUTRAL` on every
     * art-less track that reaches the now-playing screen.
     */
    @Test fun `a monochrome seed produces grey marks, never an invented hue`() {
        val m = ArtSeed.NEUTRAL.backdropMarks(ArtSeed.NEUTRAL.colors(true).bg, scrimAtText(true), true)
        listOf("accent" to m.accent, "quiet" to m.quiet).forEach { (role, c) ->
            assertTrue(
                "$role invented a hue from a zero-chroma seed: $c",
                c.red == c.green && c.green == c.blue,
            )
        }
    }
}
