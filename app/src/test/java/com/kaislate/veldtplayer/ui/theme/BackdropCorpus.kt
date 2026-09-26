// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The 6-entry cover corpus [BackdropTextTest] established `scrimAtText` with, lifted out verbatim
 * so the lyrics contrast test asserts against EXACTLY the same seeds rather than a second copy
 * that could drift. See [BackdropTextTest]'s KDoc for why each entry is there.
 */
internal object BackdropCorpus {

    fun seed(hue: Double, chroma: Double, art: Color?) =
        ArtSeed(Chromaticity(hue, chroma), emptyList(), art)

    val entries: List<Pair<String, ArtSeed>> = listOf(
        "black cover" to seed(25.0, 84.0, Color(0xFF000000)),
        "white cover" to seed(25.0, 84.0, Color(0xFFFFFFFF)),
        "saturated red" to seed(25.0, 84.0, Color(0xFFD32F2F)),
        // Achromatic seed (chroma 0) carrying a non-null mean — the shape ColorExtractor.seedOf
        // actually returns for a black-and-white cover (finding 14). Two entries, not one: a
        // black mean and a white mean bind the composited ground from opposite directions.
        "greyscale cover, black mean" to seed(0.0, 0.0, Color(0xFF000000)),
        "greyscale cover, white mean" to seed(0.0, 0.0, Color(0xFFFFFFFF)),
        "no artwork" to seed(250.0, 40.0, null),
    )

    /**
     * The PRIMARY contrast target for one (entry, theme) pair — 7:1 everywhere except the two
     * documented dark-theme ceilings (`white cover`, `greyscale cover, white mean`), held to
     * 4.5:1. See [BackdropTextTest]'s KDoc for why. ONE definition, shared by the title-band test
     * and the lyrics test, so the lyrics can never be held to a different exception list.
     */
    fun primaryFloor(name: String, isLight: Boolean): Double =
        if (!isLight && (name == "white cover" || name == "greyscale cover, white mean")) 4.5 else 7.0

    /** The composited ground: the art mean lerped under `bg` at [alpha], per sRGB channel. */
    fun groundOf(s: ArtSeed, bg: Color, alpha: Float): Color {
        val a = s.artMean ?: return bg
        fun mix(x: Float, y: Float) = x + (y - x) * alpha
        return Color(mix(a.red, bg.red), mix(a.green, bg.green), mix(a.blue, bg.blue))
    }
}
