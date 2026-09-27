// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.components.scrimAtText
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.BackdropCorpus
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.backdropMarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pill's and card's colours are Veldt's solved tones, measured against the ground each one
 * actually lands on — the opaque pill surface, and the card's composited art-under-scrim — over
 * the same cover corpus the now-playing screen's tones are held to ([BackdropCorpus]).
 */
class PillColorsTest {

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)
    private val themes = listOf(true, false)
    private val modes = listOf("auto", "accent-light", "white")

    @Test fun `pill text and marks clear their ratios against the opaque surface, both themes`() {
        val failures = BackdropCorpus.entries.flatMap { (name, s) ->
            themes.flatMap { light ->
                val r = PillColors.pill(s, light, "auto")
                listOfNotNull(
                    check("$name/$light/title", ratio(r.title, r.surface), 7.0),
                    check("$name/$light/subtitle", ratio(r.subtitle, r.surface), 4.5),
                    check("$name/$light/controls", ratio(r.controls, r.surface), 7.0),
                ) + modes.mapNotNull { m ->
                    val floor = if (m == "auto") 3.0 else 4.5
                    check("$name/$light/wave[$m]", ratio(PillColors.pill(s, light, m).wave, r.surface), floor)
                }
            }
        }
        assertEquals("pill roles short of their ratios: $failures", emptyList<String>(), failures)
    }

    @Test fun `card text and marks clear their ratios against the COMPOSITED card ground, both themes`() {
        val failures = BackdropCorpus.entries.flatMap { (name, s) ->
            themes.flatMap { light ->
                val r = PillColors.card(s, light, "auto")
                val ground = BackdropCorpus.groundOf(s, r.base, PillColors.CARD_GROUND_SCRIM)
                listOfNotNull(
                    check("$name/$light/title", ratio(r.title, ground), BackdropCorpus.primaryFloor(name, light)),
                    check("$name/$light/subtitle", ratio(r.subtitle, ground), 4.5),
                    check("$name/$light/icons", ratio(r.icons, ground), BackdropCorpus.primaryFloor(name, light)),
                    check("$name/$light/progress", ratio(r.progress, ground), 4.5),
                    check("$name/$light/track", ratio(r.track, ground), 3.0),
                ) + modes.mapNotNull { m ->
                    check("$name/$light/wave[$m]", ratio(PillColors.card(s, light, m).wave, ground), 3.0)
                }
            }
        }
        assertEquals("card roles short of their ratios: $failures", emptyList<String>(), failures)
    }

    @Test fun `the card's weakest bg share is at least the now-playing title band's`() {
        // Wisp's own scrim (0.58 -> 0.44) composites to ~0.50, below the ~0.60 crossing the
        // corpus needs; the test above is what fails if it comes back.
        themes.forEach { light ->
            assertTrue(
                "card ground share ${PillColors.CARD_GROUND_SCRIM} < scrimAtText($light) ${scrimAtText(light)}",
                PillColors.CARD_GROUND_SCRIM >= scrimAtText(light) - 0.005f,
            )
        }
    }

    @Test fun `no role is alpha-dimmed`() {
        val dimmed = BackdropCorpus.entries.flatMap { (name, s) ->
            themes.flatMap { light ->
                modes.flatMap { m ->
                    val p = PillColors.pill(s, light, m)
                    val c = PillColors.card(s, light, m)
                    listOf(p.surface, p.title, p.subtitle, p.controls, p.wave,
                        c.base, c.title, c.subtitle, c.icons, c.progress, c.track, c.wave)
                        .withIndex().filter { it.value.alpha != 1f }.map { "$name/$light/$m/#${it.index}" }
                }
            }
        }
        assertEquals(emptyList<String>(), dimmed)
    }

    @Test fun `the hierarchy survives - title and subtitle stay distinct on both surfaces`() {
        val s = BackdropCorpus.seed(25.0, 84.0, Color(0xFFD32F2F))
        themes.forEach { light ->
            val p = PillColors.pill(s, light, "auto")
            val c = PillColors.card(s, light, "auto")
            assertTrue(p.title != p.subtitle && c.title != c.subtitle)
        }
    }

    @Test fun `wave colour modes map onto the solved roles`() {
        val s = BackdropCorpus.seed(25.0, 84.0, Color(0xFFD32F2F))
        themes.forEach { light ->
            val colors = s.colors(light)
            val strong = s.backdropMarks(colors.bg, 1f, light).accent
            assertEquals(
                listOf(colors.accent, strong, colors.onBg, colors.accent),
                listOf("auto", "accent-light", "white", "something-stale").map { PillColors.pill(s, light, it).wave },
            )
        }
    }

    @Test fun `waveColor is a plain three-way switch with auto as the fallback`() {
        val std = Color.Red; val strong = Color.Green; val ink = Color.Blue
        assertEquals(
            listOf(std, strong, ink, std, std),
            listOf("auto", "accent-light", "white", "", "WHITE").map { PillColors.waveColor(it, std, strong, ink) },
        )
    }

    @Test fun `the neutral seed yields a legible pill before any art is analysed`() {
        themes.forEach { light ->
            val r = PillColors.pill(ArtSeed.NEUTRAL, light, "accent-light")
            assertTrue(ratio(r.title, r.surface) >= 7.0 && ratio(r.wave, r.surface) >= 4.5)
        }
    }

    private fun check(label: String, value: Double, floor: Double): String? =
        if (value >= floor) null else "$label=%.2f<%.1f".format(value, floor)
}
