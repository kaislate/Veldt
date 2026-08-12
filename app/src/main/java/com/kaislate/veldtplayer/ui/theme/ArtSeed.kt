// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.theme.hct.Contrast
import com.kaislate.veldtplayer.ui.theme.hct.TonalPalette
import kotlin.math.ceil
import kotlin.math.floor

/** A hue and a chroma are NOT a colour until a tone is chosen — and the tone is per theme. */
data class Chromaticity(val hue: Double, val chroma: Double)

data class ArtSeed(
    val primary: Chromaticity,
    /** Secondary hues for the wave. Never contains [primary]. */
    val wave: List<Chromaticity> = emptyList(),
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
        val accentP = TonalPalette.fromHueAndChroma(primary.hue, maxOf(primary.chroma, ACCENT_CHROMA))

        val bgTone = if (isLight) BG_TONE_LIGHT else BG_TONE_DARK
        val bg = Color(surface.tone(bgTone.toInt()))

        val onTone = solve(bgTone, RATIO_TEXT, isLight)
        val accentTone = solve(bgTone, RATIO_LARGE, isLight)

        return DominantColors(
            bg = bg,
            onBg = Color(surface.tone(onTone.toInt())),
            accent = Color(accentP.tone(accentTone.toInt())),
            waveColors = wave.map {
                Color(TonalPalette.fromHueAndChroma(it.hue, maxOf(it.chroma, ACCENT_CHROMA))
                    .tone(accentTone.toInt()))
            },
        )
    }

    companion object {
        private const val SURFACE_CHROMA = 8.0
        private const val ACCENT_CHROMA = 32.0
        private const val BG_TONE_DARK = 10.0
        private const val BG_TONE_LIGHT = 98.0
        private val RATIO_TEXT = Contrast.RATIO_45
        private val RATIO_LARGE = Contrast.RATIO_30

        /** Shown before art loads and whenever there is none. Monochrome by construction, so
         *  it derives per theme like everything else and cannot be a hardcoded dark constant. */
        val NEUTRAL = ArtSeed(Chromaticity(0.0, 0.0))

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
        private fun solve(bgTone: Double, ratio: Double, isLight: Boolean): Double {
            val solved = if (isLight) Contrast.darker(bgTone, ratio) else Contrast.lighter(bgTone, ratio)
            if (solved < 0) return if (isLight) 0.0 else 100.0
            return if (isLight) floor(solved) else ceil(solved)
        }
    }
}
