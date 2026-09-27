// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.Chromaticity
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM: [WidgetTones] is [ArtSeed.colors] mapped to widget roles. The widget's ground is a
 * solid `bg` with nothing composited under it, so contrast is measured against `ground` itself.
 */
class WidgetTonesTest {

    /** A spread of cover palettes: the hues the app's contrast work was measured on, a yellow
     *  (the lightest hue at any tone), a near-grey, and no art at all. */
    private val palettes = mapOf(
        "crimson" to ArtSeed(Chromaticity(25.0, 70.0)),
        "yellow" to ArtSeed(Chromaticity(100.0, 80.0)),
        "blue" to ArtSeed(Chromaticity(260.0, 50.0)),
        "green" to ArtSeed(Chromaticity(140.0, 40.0)),
        "grey" to ArtSeed(Chromaticity(0.0, 0.0), artMean = Color(0xFF808080)),
        "none" to ArtSeed.NEUTRAL,
    )

    private fun ratio(fg: Int, bg: Int) = ColorExtractor.contrastRatio(Color(fg), Color(bg))

    @Test fun `every text role clears 4_5 to 1 on the widget ground, in both themes`() {
        val failures = mutableListOf<String>()
        for ((name, seed) in palettes) {
            for (isLight in listOf(true, false)) {
                val t = WidgetTones.of(seed, isLight)
                val theme = if (isLight) "light" else "dark"
                val title = ratio(t.title, t.ground)
                val subtitle = ratio(t.subtitle, t.ground)
                if (title < 7.0) failures += "$name/$theme title ${"%.2f".format(title)}"
                if (subtitle < 4.5) failures += "$name/$theme artist ${"%.2f".format(subtitle)}"
                if (ratio(t.skip, t.ground) < 7.0) failures += "$name/$theme skip"
                // Non-text: WCAG 1.4.11's 3:1, which the accent is solved for.
                if (ratio(t.playPause, t.ground) < 3.0) failures += "$name/$theme play/pause"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test fun `the roles are exactly now playing's solved tones`() {
        for ((name, seed) in palettes) {
            for (isLight in listOf(true, false)) {
                val c = seed.colors(isLight)
                assertEquals(
                    "$name light=$isLight",
                    WidgetTones(c.bg.toArgb(), c.onBg.toArgb(), c.onBgSecondary.toArgb(), c.onBg.toArgb(), c.accent.toArgb()),
                    WidgetTones.of(seed, isLight),
                )
            }
        }
    }

    @Test fun `the ground follows the theme - light is light, dark is dark`() {
        for ((name, seed) in palettes) {
            val pair = WidgetTonePair.of(seed)
            assertTrue("$name light ground", luminance(pair.light.ground) > 0.8)
            assertTrue("$name dark ground", luminance(pair.dark.ground) < 0.05)
        }
    }

    @Test fun `the artist line is a distinct solved tone, not the title dimmed`() {
        for ((name, seed) in palettes) {
            val t = WidgetTones.of(seed, isLight = true)
            assertEquals("$name artist is opaque", 0xFF, t.subtitle ushr 24)
            assertTrue("$name artist differs from title", t.subtitle != t.title)
        }
    }

    private fun luminance(argb: Int): Double = ColorExtractor.contrastRatio(Color(argb), Color.Black).let { (it * 0.05) - 0.05 }

    @Test fun `the user's theme choice decides which tones fill the launcher's two slots`() {
        val pair = WidgetTonePair.of(ArtSeed(Chromaticity(25.0, 70.0)))
        assertEquals(WidgetTonePair(pair.light, pair.light), pair.resolvedFor(ThemeMode.LIGHT))
        assertEquals(WidgetTonePair(pair.dark, pair.dark), pair.resolvedFor(ThemeMode.DARK))
        assertEquals(pair, pair.resolvedFor(ThemeMode.SYSTEM))
    }
}
