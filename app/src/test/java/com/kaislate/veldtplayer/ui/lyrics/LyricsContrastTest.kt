// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.components.scrimAtFraction
import com.kaislate.veldtplayer.ui.theme.BackdropCorpus
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.backdropText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM. Lyric tones solved at the scrim of the TOPMOST lyric line, over the same corpus
 * `BackdropTextTest` uses, in both themes — spec §7's "active ≥ 7:1, inactive ≥ 4.5:1".
 *
 * Asserted at `yFraction = 0`: the gradient's top stop, the weakest scrim anywhere on the
 * backdrop, and so a floor for any lyrics region (the full-screen route's lyrics begin just
 * under its header; the pane begins at the artwork slot, further down, under more scrim).
 *
 * **Not weakened, and not all green.** At the top stop the solver CANNOT reach the targets for
 * the (entry, theme, role) cases in [ceilings]: the composited ground there is a mid tone the
 * theme's text polarity cannot clear by any margin. For each one the test asserts the solver
 * already chose the extreme tone of its polarity (pure black in light, pure white in dark) — so
 * no tone does better, a provable ceiling rather than a solver regression — and pins the set and
 * its ratios exactly, so closing one (or opening another) must update this list. The numbers are
 * in the Task 5 fix report for the controller's ruling.
 */
class LyricsContrastTest {

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)

    /** (entry, isLight, role) to the ratio the extreme tone reaches at the top stop. */
    private val ceilings = mapOf(
        Triple("black cover", true, "primary") to 5.99,
        Triple("greyscale cover, black mean", true, "primary") to 6.00,
        Triple("white cover", false, "primary") to 2.17,
        Triple("white cover", false, "secondary") to 2.17,
        Triple("greyscale cover, white mean", false, "primary") to 2.19,
        Triple("greyscale cover, white mean", false, "secondary") to 2.19,
    )

    @Test fun `lyric tones at the top of the backdrop - targets met, or a pinned provable ceiling`() {
        val below = mutableMapOf<Triple<String, Boolean, String>, Double>()
        val notExtreme = mutableListOf<String>()
        for ((name, s) in BackdropCorpus.entries) for (light in listOf(true, false)) {
            val alpha = scrimAtFraction(light, 0f)
            val bg = s.colors(light).bg
            val ground = BackdropCorpus.groundOf(s, bg, alpha)
            val t = s.backdropText(bg, alpha, light)
            val extreme = if (light) Color.Black else Color.White
            for ((role, tone, target) in listOf(
                Triple("primary", t.primary, 7.0),
                Triple("secondary", t.secondary, 4.5),
            )) {
                val r = ratio(tone, ground)
                if (r < target) {
                    below[Triple(name, light, role)] = r
                    if (tone != extreme) notExtreme += "$name/$light/$role=%.2f".format(r)
                }
            }
        }
        assertEquals("a shortfall where a better tone existed: $notExtreme", emptyList<String>(), notExtreme)
        assertEquals("the set of ceilings changed: $below", ceilings.keys, below.keys)
        ceilings.forEach { (k, v) -> assertEquals("ratio for $k", v, below.getValue(k), 0.01) }
    }

    /** Where the lyric tones DO meet both targets for the whole corpus: the lowest fraction per
     *  theme, pinned so the report's "how far down would lyrics have to start" is measured. */
    @Test fun `the first backdrop position where every corpus entry meets both targets`() {
        fun allMeet(light: Boolean, y: Float): Boolean = BackdropCorpus.entries.all { (_, s) ->
            val alpha = scrimAtFraction(light, y)
            val bg = s.colors(light).bg
            val ground = BackdropCorpus.groundOf(s, bg, alpha)
            val t = s.backdropText(bg, alpha, light)
            ratio(t.primary, ground) >= 7.0 && ratio(t.secondary, ground) >= 4.5
        }
        fun firstMeeting(light: Boolean): Float? =
            (0..100).map { it / 100f }.firstOrNull { allMeet(light, it) }
        // Light: black-cover primary crosses 7:1 at alpha ≈ 0.599 (see scrimAtText's KDoc) —
        // 0.55 + 0.40 * y ≥ 0.599 at y ≈ 0.13.
        assertEquals(0.13f, firstMeeting(true)!!, 0.011f)
        // Dark: white-cover primary needs alpha ≈ 0.73 (scrimAtText's KDoc) — y ≈ 0.85 — so the
        // full 7:1 promise holds only in the bottom sixth of the frame.
        assertEquals(0.85f, firstMeeting(false)!!, 0.011f)
    }
}
