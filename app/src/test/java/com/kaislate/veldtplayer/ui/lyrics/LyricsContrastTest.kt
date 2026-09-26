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
 * **The shipped behaviour** (Task 6a fix round): a lyrics region draws `bg` at
 * [lyricsFloorAlpha] so the composited scrim under every line is at least [lyricsTargetScrim]
 * (0.85 light / 0.70 dark), while the tones ([lyricsBackdropText]) are solved against the title
 * band's MODELLED ground at `scrimAtText` — the floor is margin. The margin test checks the tones
 * against EVERY ground a line can sit under, alpha `scrimAtText..1`, over the corpus plus a
 * mid-grey cover modelled on the device case (Sleep Token "Chokehold", light), which is the entry
 * the "solve at the drawn alpha" control reddens.
 *
 * **Without the floor** (why it exists, kept falsifiable): solving at the top stop alone, the
 * solver CANNOT reach the targets for the cases in [ceilingsWithoutFloor] — it already chose
 * the extreme tone of its polarity there (pure black in light, pure white in dark), so no tone
 * does better. The set and ratios are pinned exactly.
 */
class LyricsContrastTest {

    private fun ratio(a: Color, b: Color) = ColorExtractor.contrastRatio(a, b)

    /** The title band's own primary targets — [BackdropCorpus.primaryFloor], the one shared
     *  definition: the lyrics carry exactly the title's documented exceptions and no others. */
    private fun titlePrimaryFloor(name: String, isLight: Boolean): Double =
        BackdropCorpus.primaryFloor(name, isLight)

    /** A mid-grey cover like the device case (Task 6a: light, "Chokehold", drawn ground ~239 at
     *  alpha 0.85, i.e. an art mean of roughly 177 grey). Kept local: the shared corpus also feeds
     *  the title-band and top-stop tables, which this entry has no business changing. */
    private val deviceLike = "mid-grey cover (device case)" to BackdropCorpus.seed(30.0, 6.0, Color(0xFFB4AFAA))

    private val lyricsCorpus = BackdropCorpus.entries + deviceLike

    /** Every alpha a lyric line's ground can have: from the title band's model up to `bg` itself. */
    private fun groundAlphas(light: Boolean): List<Float> {
        val from = scrimAtText(light)
        return (0..100).map { from + (1f - from) * it / 100f }
    }

    @Test fun `the lyric tones meet the targets against every ground a line can sit under`() {
        val failures = mutableListOf<String>()
        for ((name, s) in lyricsCorpus) for (light in listOf(true, false)) {
            val bg = s.colors(light).bg
            val t = s.lyricsBackdropText(bg, light)
            for (alpha in groundAlphas(light)) {
                val ground = BackdropCorpus.groundOf(s, bg, alpha)
                val p = ratio(t.primary, ground)
                val q = ratio(t.secondary, ground)
                if (p < titlePrimaryFloor(name, light)) failures += "$name/$light/a=%.2f/primary=%.2f".format(alpha, p)
                if (q < 4.5) failures += "$name/$light/a=%.2f/secondary=%.2f".format(alpha, q)
            }
        }
        val entries = failures.map { it.substringBefore("/a=") }.distinct()
        assertEquals("lyric tones failed for $entries, e.g. ${failures.take(4)}", emptyList<String>(), failures)
    }

    /**
     * The per-theme argument in [lyricsBackdropText]'s KDoc, as a test: against the DRAWN ground
     * (the floor's target), contrast is at least what it is against the MODELLED ground whenever
     * the art's mean lies on the far side of `bg` from the ink — and the set where it does NOT is
     * exactly the covers more extreme than `bg` (near-white in light, near-black in dark), the
     * reason the tones are also solved at `bg` itself.
     */
    @Test fun `drawn contrast is at least modelled contrast, except for covers more extreme than bg`() {
        val notMonotone = mutableSetOf<Pair<String, Boolean>>()
        for ((name, s) in lyricsCorpus) for (light in listOf(true, false)) {
            val bg = s.colors(light).bg
            val t = s.lyricsBackdropText(bg, light)
            val modelled = BackdropCorpus.groundOf(s, bg, scrimAtText(light))
            val drawn = BackdropCorpus.groundOf(s, bg, lyricsTargetScrim(light))
            for (tone in listOf(t.primary, t.secondary)) {
                if (ratio(tone, drawn) < ratio(tone, modelled) - 1e-9) notMonotone += name to light
            }
        }
        assertEquals(
            setOf(
                "white cover" to true,
                "greyscale cover, white mean" to true,
                "black cover" to false,
                "greyscale cover, black mean" to false,
            ),
            notMonotone,
        )
    }

    /** For an ordinary cover the tones ARE the title band's model solve — the bg-end solve only
     *  ever steps in for the extreme covers above. */
    @Test fun `for the device-like cover the tones are exactly the scrimAtText solve`() {
        val (_, s) = deviceLike
        for (light in listOf(true, false)) {
            val bg = s.colors(light).bg
            assertEquals(s.backdropText(bg, scrimAtText(light), light), s.lyricsBackdropText(bg, light))
        }
    }

    @Test fun `the floor lifts the top stop to exactly the lyrics target, in both themes`() {
        for (light in listOf(true, false)) {
            val aTop = scrimAtFraction(light, 0f)
            assertEquals(lyricsTargetScrim(light), compositeScrim(aTop, lyricsFloorAlpha(light, 0f)), 1e-5f)
        }
    }

    /** Task 6a: headroom over the title band — max(scrimAtText, LYRICS_MIN_SCRIM) per theme. */
    @Test fun `the lyrics target is max(scrimAtText, the lyrics minimum) - light 0_85, dark 0_70`() {
        assertEquals(0.85f, lyricsTargetScrim(true), 1e-6f)
        assertEquals(0.70f, lyricsTargetScrim(false), 1e-6f)
        for (light in listOf(true, false)) assert(lyricsTargetScrim(light) >= scrimAtText(light))
    }

    /** And at every region position the composite reaches the target (the floor is 0 where the
     *  backdrop already supplies it, and never takes the composite BELOW the backdrop's own). */
    @Test fun `the floor reaches the lyrics target at every region position`() {
        for (light in listOf(true, false)) for (i in 0..20) {
            val y = i / 20f
            val aTop = scrimAtFraction(light, y)
            val composite = compositeScrim(aTop, lyricsFloorAlpha(light, y))
            assert(composite >= lyricsTargetScrim(light) - 1e-5f) { "$light y=$y composite=$composite" }
            assert(composite >= aTop - 1e-6f)
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
