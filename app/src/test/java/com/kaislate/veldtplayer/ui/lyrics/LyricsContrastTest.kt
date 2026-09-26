// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.components.scrimAtFraction
import com.kaislate.veldtplayer.ui.components.scrimAtText
import com.kaislate.veldtplayer.ui.theme.BackdropCorpus
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.backdropText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM. Lyric contrast over the same corpus `BackdropTextTest` uses, in both themes — spec
 * §7's "active ≥ 7:1, inactive ≥ 4.5:1".
 *
 * **With the floor** (the shipped behaviour): a lyrics region draws `bg` at [lyricsFloorAlpha]
 * over the backdrop, so the composited scrim under its TOPMOST line — the backdrop's top stop,
 * `yFraction 0`, the weakest anywhere and so a floor for either surface — is lifted to
 * `scrimAtText`, and the tones are solved at `scrimAtText` exactly like the title. The ground is
 * built from the COMPOSITED alpha ([compositeScrim] of the backdrop's own scrim and the floor),
 * so removing the floor from that alpha is what this test would catch.
 *
 * **Without the floor** (why it exists, kept falsifiable): solving at the top stop alone, the
 * solver CANNOT reach the targets for the cases in [ceilingsWithoutFloor] — it already chose
 * the extreme tone of its polarity there (pure black in light, pure white in dark), so no tone
 * does better. The set and ratios are pinned exactly.
 */
class LyricsContrastTest {

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)

    /** The title band's own primary targets — `BackdropTextTest.primaryFloor`, verbatim: dark
     *  white-mean covers are held to 4.5:1 there (a documented tone ceiling at 0.62), and the
     *  lyrics now inherit exactly that guarantee, no more and no less. */
    private fun titlePrimaryFloor(name: String, isLight: Boolean): Double =
        if (!isLight && (name == "white cover" || name == "greyscale cover, white mean")) 4.5 else 7.0

    @Test fun `with the floor - every corpus seed meets the title band's targets at the composited alpha`() {
        val failures = mutableListOf<String>()
        for ((name, s) in BackdropCorpus.entries) for (light in listOf(true, false)) {
            val aTop = scrimAtFraction(light, 0f)
            val composited = compositeScrim(aTop, lyricsFloorAlpha(light, 0f))
            val bg = s.colors(light).bg
            val ground = BackdropCorpus.groundOf(s, bg, composited)
            val t = s.backdropText(bg, scrimAtText(light), light)
            val p = ratio(t.primary, ground)
            val q = ratio(t.secondary, ground)
            if (p < titlePrimaryFloor(name, light)) failures += "$name/$light/primary=%.2f".format(p)
            if (q < 4.5) failures += "$name/$light/secondary=%.2f".format(q)
        }
        assertEquals("lyric tones failed at the composited alpha: $failures", emptyList<String>(), failures)
    }

    @Test fun `the floor lifts the top stop to exactly scrimAtText, in both themes`() {
        for (light in listOf(true, false)) {
            val aTop = scrimAtFraction(light, 0f)
            assertEquals(scrimAtText(light), compositeScrim(aTop, lyricsFloorAlpha(light, 0f)), 1e-5f)
        }
    }

    /** (entry, isLight, role) to the ratio the extreme tone reaches at the top stop, no floor. */
    private val ceilingsWithoutFloor = mapOf(
        Triple("black cover", true, "primary") to 5.99,
        Triple("greyscale cover, black mean", true, "primary") to 6.00,
        Triple("white cover", false, "primary") to 2.17,
        Triple("white cover", false, "secondary") to 2.17,
        Triple("greyscale cover, white mean", false, "primary") to 2.19,
        Triple("greyscale cover, white mean", false, "secondary") to 2.19,
    )

    @Test fun `without the floor - the top stop has a pinned provable shortfall`() {
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
        assertEquals("the set of ceilings changed: $below", ceilingsWithoutFloor.keys, below.keys)
        ceilingsWithoutFloor.forEach { (k, v) -> assertEquals("ratio for $k", v, below.getValue(k), 0.01) }
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
