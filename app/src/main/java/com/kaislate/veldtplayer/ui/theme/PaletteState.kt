// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.kaislate.veldtplayer.ui.motion.Motion

/**
 * The extractor yields 0..5 wave swatches depending on the artwork. Animating a
 * variable-length list would restructure composition on every track change, so the
 * palette is projected onto a FIXED number of slots and short lists repeat their
 * last colour. Pure, so the reconciliation rule is unit-tested.
 */
object PaletteSlots {
    /** Matches the cap `ColorExtractor.seedOf` puts on `ArtSeed.wave` (`MAX_WAVE_COLORS`). */
    const val SLOT_COUNT = 5

    fun slot(colors: List<Color>, index: Int, fallback: Color): Color =
        colors.getOrNull(index) ?: colors.lastOrNull() ?: fallback
}

/**
 * Animates every channel of [target] toward its new value over ~600ms (spec §6).
 *
 * Everything on ONE surface — backdrop, accent, wave colours, art placeholder — reads the
 * same returned instance, so a screen drifts as a single object instead of each element
 * snapping independently. This is the single cheapest thing that separates Veldt from every
 * player that hard-cuts its colours on track change.
 *
 * The now-playing screen and the mini-player call this separately rather than sharing one
 * instance, because hoisting it to the nav host would recompose the entire scaffold and the
 * NavHost on every frame of the drift. They cannot visibly disagree — same target, same
 * spec — and they are never both the focus.
 */
@Composable
fun rememberAnimatedPalette(target: DominantColors): DominantColors {
    val bg by animateColorAsState(target.bg, Motion.palette, label = "paletteBg")
    val onBg by animateColorAsState(target.onBg, Motion.palette, label = "paletteOnBg")
    val onBgSecondary by animateColorAsState(target.onBgSecondary, Motion.palette, label = "paletteOnBgSecondary")
    val accent by animateColorAsState(target.accent, Motion.palette, label = "paletteAccent")

    // Fixed slot count keeps the composition structure stable across track changes.
    val w0 by animateColorAsState(PaletteSlots.slot(target.waveColors, 0, target.accent), Motion.palette, label = "wave0")
    val w1 by animateColorAsState(PaletteSlots.slot(target.waveColors, 1, target.accent), Motion.palette, label = "wave1")
    val w2 by animateColorAsState(PaletteSlots.slot(target.waveColors, 2, target.accent), Motion.palette, label = "wave2")
    val w3 by animateColorAsState(PaletteSlots.slot(target.waveColors, 3, target.accent), Motion.palette, label = "wave3")
    val w4 by animateColorAsState(PaletteSlots.slot(target.waveColors, 4, target.accent), Motion.palette, label = "wave4")

    // Emit only as many wave colours as the target actually has, so a two-swatch
    // track doesn't render five near-identical filaments.
    val count = target.waveColors.size.coerceIn(0, PaletteSlots.SLOT_COUNT)
    val waves = listOf(w0, w1, w2, w3, w4).take(count)

    return DominantColors(bg = bg, onBg = onBg, onBgSecondary = onBgSecondary, accent = accent, waveColors = waves)
}
