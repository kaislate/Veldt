// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.components.SCRIM_AT_TEXT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Text over the now-playing backdrop, which is NOT `bg`: it is the blurred cover with `bg` over it
 * at a scrim. `ArtSeed.colors` solves against `bg`, so on a chromatic cover in light theme the
 * measured contrasts were title 3.69:1, artist 2.37:1, elapsed 2.80:1 — all short of 4.5:1.
 *
 * These assertions cannot prove legibility of a rendered frame; only a device measurement can, and
 * that is Task 3. What they prove is that the SOLVE targets the real ground.
 *
 * This is also the corpus that established [SCRIM_AT_TEXT]'s value: an earlier draft solved
 * against `scrim.top` (the alpha at y=0, where the album art sits, not text) and found that no
 * foreground tone can clear 7:1/4.5:1 against the ground that produces at that alpha — a real
 * ceiling (`Contrast.ratioOfTones`), not an implementation bug. Sweeping this same corpus against
 * `scrimAlpha` found the whole corpus, both themes, first clears everywhere around `alpha ≈ 0.73`
 * (bound by white-cover/dark theme, the mirror image of black-cover/light theme); [SCRIM_AT_TEXT]
 * is `0.75`, a round value with margin above that boundary.
 */
class BackdropTextTest {

    private fun seed(hue: Double, chroma: Double, art: Color?) =
        ArtSeed(Chromaticity(hue, chroma), emptyList(), art)

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)

    /** The weakest scrim any text sits under — see [SCRIM_AT_TEXT] for why this isn't `scrim.top`. */
    private val SCRIM = SCRIM_AT_TEXT

    private val corpus = listOf(
        "black cover" to seed(25.0, 84.0, Color(0xFF000000)),
        "white cover" to seed(25.0, 84.0, Color(0xFFFFFFFF)),
        "saturated red" to seed(25.0, 84.0, Color(0xFFD32F2F)),
        "near-grey" to seed(180.0, 2.0, Color(0xFF808080)),
        "no artwork" to seed(250.0, 40.0, null),
    )

    private fun groundOf(s: ArtSeed, bg: Color, alpha: Float): Color {
        val a = s.artMean ?: return bg
        fun mix(x: Float, y: Float) = x + (y - x) * alpha
        return Color(mix(a.red, bg.red), mix(a.green, bg.green), mix(a.blue, bg.blue))
    }

    @Test fun `both tones clear their ratios against the COMPOSITED ground, in both themes`() {
        val failures = corpus.flatMap { (name, s) ->
            listOf(true, false).flatMap { light ->
                val bg = s.colors(light).bg
                val ground = groundOf(s, bg, SCRIM)
                val t = s.backdropText(bg, SCRIM, light)
                listOfNotNull(
                    ratio(t.primary, ground).let { if (it >= 7.0) null else "$name/${light}/primary=%.2f".format(it) },
                    ratio(t.secondary, ground).let { if (it >= 4.5) null else "$name/${light}/secondary=%.2f".format(it) },
                )
            }
        }
        assertEquals("tones failed against the composited ground: $failures", emptyList<String>(), failures)
    }

    @Test fun `hierarchy survives — the two tones stay distinct`() {
        val s = seed(25.0, 84.0, Color(0xFFD32F2F))
        val t = s.backdropText(s.colors(true).bg, SCRIM, isLight = true)
        assertNotEquals("a collapse to one tone erases the title/artist distinction", t.primary, t.secondary)
    }

    @Test fun `no artwork is identical to solving against bg — the common path cannot regress`() {
        val s = seed(250.0, 40.0, null)
        val bg = s.colors(true).bg
        val t = s.backdropText(bg, SCRIM, isLight = true)
        val c = s.colors(true)
        // artMean == null means ground == bg, so the tones must match colors()' own solves.
        assertEquals(listOf(c.onBg, c.onBgSecondary), listOf(t.primary, t.secondary))
    }
}
