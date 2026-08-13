// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.kaislate.veldtplayer.ui.theme.hct.Contrast
import com.kaislate.veldtplayer.ui.theme.hct.Hct
import com.kaislate.veldtplayer.ui.theme.hct.TonalPalette
import kotlin.math.ceil
import kotlin.math.floor

/** A hue and a chroma are NOT a colour until a tone is chosen — and the tone is per theme. */
data class Chromaticity(val hue: Double, val chroma: Double)

data class ArtSeed(
    val primary: Chromaticity,
    /** Secondary hues for the wave. Never contains [primary]. */
    val wave: List<Chromaticity> = emptyList(),
    /** Population-weighted mean of the artwork's swatches; null when there is no artwork. */
    val artMean: Color? = null,
) {
    /** Derived, not stored, so it cannot disagree with [Chromaticity.chroma]. */
    val isMonochrome: Boolean get() = primary.chroma == 0.0

    /**
     * Every role at a solved tone of this seed's hue.
     *
     * Contrast is CONSTRUCTED here, not repaired afterwards: [Contrast.lighter]/[Contrast.darker]
     * return the tone achieving a required ratio against the ground, so legibility is a property
     * of the arithmetic rather than of an iterative walk that may run out of room.
     */
    fun colors(isLight: Boolean): DominantColors {
        val surface = TonalPalette.fromHueAndChroma(primary.hue, minOf(primary.chroma, SURFACE_CHROMA))
        val accentP = TonalPalette.fromHueAndChroma(primary.hue, accentChroma(primary))

        val bgTone = if (isLight) BG_TONE_LIGHT else BG_TONE_DARK
        val bg = Color(surface.tone(bgTone.toInt()))

        val onTone = solve(bgTone, RATIO_TEXT, isLight)          // 7:1 (AAA body) — unchanged name, raised ratio
        val secondaryTone = solve(bgTone, RATIO_SECONDARY, isLight)
        val accentTone = solve(bgTone, RATIO_LARGE, isLight)

        return DominantColors(
            bg = bg,
            onBg = Color(surface.tone(onTone.toInt())),
            onBgSecondary = Color(surface.tone(secondaryTone.toInt())),
            accent = Color(accentP.tone(accentTone.toInt())),
            waveColors = wave.map {
                Color(TonalPalette.fromHueAndChroma(it.hue, accentChroma(it)).tone(accentTone.toInt()))
            },
        )
    }

    companion object {
        internal const val SURFACE_CHROMA = 8.0
        private const val ACCENT_CHROMA = 32.0
        private const val BG_TONE_DARK = 10.0
        private const val BG_TONE_LIGHT = 98.0
        private val RATIO_TEXT = Contrast.RATIO_70
        private val RATIO_LARGE = Contrast.RATIO_30

        /** The subtitle's ratio against `bg`. AA body text — the artist line is text, not decoration.
         *  It is SOLVED rather than alpha-dimmed: the old approach multiplied, at a fixed 0.7 strength,
         *  a tone that was solved for exactly 4.5:1 down toward the ground, landing near 3.1:1 with no
         *  artwork involved. Hierarchy now comes from the RATIO difference (7:1 vs 4.5:1 on the backdrop,
         *  and primary-vs-secondary here), not from making a label illegible. */
        private val RATIO_SECONDARY = Contrast.RATIO_45

        /** Shown before art loads and whenever there is none. Monochrome by construction, so
         *  it derives per theme like everything else and cannot be a hardcoded dark constant. */
        val NEUTRAL = ArtSeed(Chromaticity(0.0, 0.0))

        /**
         * The chroma an accent's [TonalPalette] is built from. [ACCENT_CHROMA] is a FLOOR —
         * so a washed-out swatch still reads as an accent — but the floor must NOT apply to a
         * genuinely monochrome [Chromaticity] (`chroma == 0.0`): flooring a zero would invent
         * a hue neither [ColorExtractor] nor the caller ever supplied. [NEUTRAL] is exactly
         * that case, and every browse surface renders it — a hue invented here is not a
         * subtle bug, it is the whole app's neutral accent turning some arbitrary colour.
         */
        private fun accentChroma(c: Chromaticity): Double =
            if (c.chroma == 0.0) 0.0 else maxOf(c.chroma, ACCENT_CHROMA)

        /**
         * The tone that clears [ratio] against [bgTone]. On a light ground the foreground must
         * DARKEN; on a dark ground it must lighten. When the solver reports the ratio
         * unreachable it returns a negative sentinel and we clamp to the extreme available —
         * maximum contrast, rather than a silently illegible colour.
         *
         * The result is rounded toward the CONTRASTING extreme — ceil when lightening, floor
         * when darkening — rather than truncated, so the caller's `.toInt()` tone lookup can
         * only overshoot the requested ratio, never undershoot it. [Contrast]'s own tolerance
         * (0.4 tone) is a buffer for gamut mapping, not for a lost fraction of a tone from
         * plain truncation, which can exceed it.
         */
        internal fun solve(bgTone: Double, ratio: Double, isLight: Boolean): Double {
            val solved = if (isLight) Contrast.darker(bgTone, ratio) else Contrast.lighter(bgTone, ratio)
            if (solved < 0) return if (isLight) 0.0 else 100.0
            return if (isLight) floor(solved) else ceil(solved)
        }
    }
}

/** Primary and secondary text for a surface whose ground is the artwork under [bg]. */
data class BackdropText(val primary: Color, val secondary: Color)

/**
 * Text tones solved against the ground this text ACTUALLY lands on.
 *
 * [ArtSeed.colors] solves against `bg`, which is right for every surface that draws `bg`. The
 * now-playing backdrop does not: it draws the blurred cover with `bg` over it at [scrimAlpha].
 * Solving against `bg` there produced measured contrasts of 3.69 / 2.37 / 2.80:1 in light theme
 * on a crimson cover.
 *
 * [scrimAlpha] is the scrim's WEAKEST value, so the result is a floor rather than an estimate —
 * every glyph sits under at least that much scrim (spec §4.4).
 */
fun ArtSeed.backdropText(bg: Color, scrimAlpha: Float, isLight: Boolean): BackdropText {
    val ground = artMean?.let { a ->
        fun mix(x: Float, y: Float) = x + (y - x) * scrimAlpha
        Color(mix(a.red, bg.red), mix(a.green, bg.green), mix(a.blue, bg.blue))
    } ?: bg
    // A tone IS luminance (L*), and contrast depends only on luminance — so converting the
    // composited colour to a tone lets the vendored solver work against an arbitrary ground.
    val groundTone = Hct.fromInt(ground.toArgb()).tone
    val surface = TonalPalette.fromHueAndChroma(primary.hue, minOf(primary.chroma, ArtSeed.SURFACE_CHROMA))
    return BackdropText(
        primary = Color(surface.tone(ArtSeed.solve(groundTone, Contrast.RATIO_70, isLight).toInt())),
        secondary = Color(surface.tone(ArtSeed.solve(groundTone, Contrast.RATIO_45, isLight).toInt())),
    )
}
