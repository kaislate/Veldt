// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import com.kaislate.veldtplayer.ui.theme.hct.Hct
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The colours every Veldt surface themes itself from, for one piece of album art.
 *
 * Marked [Immutable] so Compose can skip consumers while the palette is at rest; the
 * [waveColors] list is always built fresh and never mutated after construction.
 */
@Immutable
data class DominantColors(
    val bg: Color,
    val onBg: Color,
    val onBgSecondary: Color,
    val accent: Color,
    val waveColors: List<Color> = emptyList(),
)

/**
 * Material's disabled-content strength, applied to palette tints by [onBgFor].
 *
 * `internal` rather than private because [onBgFor] only covers [DominantColors.onBg], and
 * the queue sheet dims a whole inert list — accent tints and artwork included. One constant
 * for "this control is dead", for the reason [onBgFor] exists at all: a second literal
 * somewhere else is a disabled state that drifts out of step with every other one.
 */
internal const val DISABLED_ALPHA = 0.38f

/**
 * [DominantColors.onBg], dimmed when the control it tints is disabled.
 *
 * One definition because it is easy to get silently wrong: Compose signals "disabled" by
 * lowering `LocalContentColor`, and every transport icon in the app passes an explicit
 * palette tint that OVERRIDES that — so a dead button renders identically to a live one
 * unless the call site dims it itself.
 */
fun DominantColors.onBgFor(enabled: Boolean): Color =
    if (enabled) onBg else onBg.copy(alpha = DISABLED_ALPHA)

/**
 * Derives a theme-independent [ArtSeed] from album art.
 *
 * Original implementation, written from a behavioural specification — it is not
 * derived from any third-party source, so Veldt carries no attribution obligation
 * for this file.
 *
 * [seedOf] walks every pixel of the bitmap via [Palette]; it must never be called on
 * the main thread. Use `PaletteCache.seedFor`, which dispatches it and memoises the
 * result. Callers must keep the bitmap alive and unrecycled across the call.
 *
 * [Palette] cannot sample a `Config.HARDWARE` bitmap, so [seedOf] converts one itself
 * before generating. It does NOT rely on callers passing `allowHardware(false)`: that
 * flag governs only Coil's own decoder, and `AlbumArtFetcher` returns a fully-formed
 * `DrawableResult` that bypasses the decoder entirely — so the flag would be a no-op on
 * the app's main art path. Guarding here covers every caller.
 */
object ColorExtractor {

    /** Below this chroma a swatch reads as grey, not a colour — it must not seed a hue. */
    private const val MONOCHROME_CHROMA = 8.0

    /**
     * Ceiling applied to a swatch's chroma before it is weighted by population. Uncapped,
     * one near-fluorescent pixel cluster outscores the colour the cover actually reads as;
     * population is the honest signal, chroma only breaks ties so a large grey mass cannot
     * win outright.
     */
    private const val CHROMA_CEILING = 48.0

    /**
     * Minimum hue separation, in degrees, between kept wave stops. Without it a one-colour
     * cover yields five near-identical stops and the scrub bar reads flat.
     */
    private const val MIN_HUE_SEPARATION = 15.0

    /** Ceiling on scrub-bar gradient stops; mirrored by `PaletteSlots.SLOT_COUNT`. */
    private const val MAX_WAVE_COLORS = 5

    /**
     * Bounds of Material's "universally disliked" dark yellow-green band and the chroma
     * above which a colour in it actually reads as bile rather than an inoffensive olive —
     * copied from `DislikeAnalyzer.isDisliked` in Material Color Utilities (Palmer & Schloss
     * 2010), which this codebase no longer vendors. See [escapeDislikedHue] for why: their
     * fix adjusts TONE, and this pipeline has nowhere to keep one.
     */
    private const val DISLIKED_HUE_LOW = 90.0
    private const val DISLIKED_HUE_HIGH = 111.0
    private const val DISLIKED_CHROMA_THRESHOLD = 16.0

    /** Escape hues for [escapeDislikedHue] — the nearer edge of the disliked band. */
    private const val DISLIKED_HUE_ESCAPE_LOW = 85.0
    private const val DISLIKED_HUE_ESCAPE_HIGH = 115.0

    /**
     * The theme-independent seed for [bitmap]. Returns [ArtSeed.NEUTRAL] — `artMean == null`
     * — only when there is genuinely no artwork to composite: [bitmap] is null or cannot be
     * read. When every swatch Palette found is too close to grey to seed a hue, the seed is
     * still achromatic (it must not invent a hue), but it DOES carry a non-null [ArtSeed.artMean]:
     * the backdrop still draws that cover, so text solved against it still needs the real
     * composited ground, not `bg` alone.
     */
    fun seedOf(bitmap: Bitmap?): ArtSeed {
        if (bitmap == null) return ArtSeed.NEUTRAL
        val readable = toReadable(bitmap) ?: return ArtSeed.NEUTRAL
        val palette = Palette.from(readable).clearFilters().generate()

        // The backdrop composites the blurred cover with `bg` per channel in sRGB (see
        // ArtSeed.backdropText), so the mean must be taken the same way — over every swatch
        // Palette found, unfiltered, since a near-grey region is still part of what is under
        // the blur even though it cannot seed a hue. Computed BEFORE the monochrome check
        // below, and unconditionally: a desaturated cover still has a mean and the backdrop
        // still draws it, so a grey cover must not fall back to a null mean just because it
        // seeds no hue — that was exactly the bug this hoist fixes.
        val artMean = meanColor(palette.swatches)

        // population x capped chroma. The cap matters: uncapped, one near-fluorescent pixel
        // cluster outscores the colour the cover actually reads as. Population is the honest
        // signal; chroma only breaks ties so a large grey mass cannot win outright.
        val ranked = palette.swatches
            .map { it to Hct.fromInt(it.rgb) }
            .filter { (_, hct) -> hct.chroma >= MONOCHROME_CHROMA }
            .sortedByDescending { (sw, hct) -> sw.population * minOf(hct.chroma, CHROMA_CEILING) }

        // Every candidate was near-grey: theme grey. Do NOT amplify noise into a hue — but DO
        // keep artMean; only bitmap == null / an unreadable bitmap (handled above, before
        // Palette ever ran) is genuinely art-less and gets NEUTRAL's null mean.
        if (ranked.isEmpty()) return ArtSeed(Chromaticity(0.0, 0.0), emptyList(), artMean)

        val primaryHct = ranked.first().second
        val primaryHue = escapeDislikedHue(primaryHct.hue, primaryHct.chroma)
        val kept = mutableListOf(primaryHue)
        val wave = mutableListOf<Chromaticity>()
        for ((_, hct) in ranked.drop(1)) {
            if (wave.size >= MAX_WAVE_COLORS) break
            val hue = escapeDislikedHue(hct.hue, hct.chroma)
            // Without a separation rule a one-colour cover yields five near-identical stops
            // and the scrub bar reads flat.
            if (kept.none { separation(it, hue) < MIN_HUE_SEPARATION }) {
                kept += hue
                wave += Chromaticity(hue, hct.chroma)
            }
        }
        return ArtSeed(Chromaticity(primaryHue, primaryHct.chroma), wave, artMean)
    }

    /**
     * Population-weighted mean of [swatches], per channel in sRGB, or null when there is
     * nothing to weight — [swatches] is empty, or every swatch in it reports zero population.
     * [seedOf] now calls this before it knows whether `ranked` (filtered from the same list)
     * is non-empty, so unlike the version this replaced, [swatches] is NOT guaranteed non-empty
     * here: a real bitmap can make Palette return zero swatches. Dividing by a zero
     * `totalPopulation` would produce NaN channels rather than throwing, so this returns null
     * instead and the caller treats that exactly like "no artwork" — correct, since there is no
     * artwork to composite into the ground either way.
     */
    private fun meanColor(swatches: List<Palette.Swatch>): Color? {
        val totalPopulation = swatches.sumOf { it.population }.toDouble()
        if (totalPopulation <= 0.0) return null
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (swatch in swatches) {
            val weight = swatch.population / totalPopulation
            val c = Color(swatch.rgb)
            r += c.red * weight
            g += c.green * weight
            b += c.blue * weight
        }
        return Color(r.toFloat(), g.toFloat(), b.toFloat())
    }

    /**
     * Moves [hue] off the disliked dark-yellow-green band when [chroma] is high enough for
     * it to actually read as bile, otherwise returns [hue] unchanged.
     *
     * This is Veldt's OWN fix, not Material's: `DislikeAnalyzer.fixIfDisliked` (formerly
     * vendored here) escapes the band by lightening TONE to 70, which cannot work in a
     * pipeline that seeds only hue and chroma — [Chromaticity] has no tone field, because
     * tone is solved later, per theme, for contrast (see [ArtSeed.colors]). Landing every
     * disliked colour on tone 70 would also fight that solve outright: against a tone-98
     * light ground, tone 70 does not even clear 3:1. Moving the HUE instead survives into
     * the seed unmodified and needs no tone to work.
     *
     * [DISLIKED_HUE_LOW], [DISLIKED_HUE_HIGH] and [DISLIKED_CHROMA_THRESHOLD] are the same
     * numbers `DislikeAnalyzer.isDisliked` used; only the fix itself is different.
     */
    private fun escapeDislikedHue(hue: Double, chroma: Double): Double {
        if (chroma <= DISLIKED_CHROMA_THRESHOLD) return hue
        if (hue < DISLIKED_HUE_LOW || hue > DISLIKED_HUE_HIGH) return hue
        val bandMidpoint = (DISLIKED_HUE_LOW + DISLIKED_HUE_HIGH) / 2.0
        return if (hue < bandMidpoint) DISLIKED_HUE_ESCAPE_LOW else DISLIKED_HUE_ESCAPE_HIGH
    }

    /** Shortest angular distance between two hues, in degrees. */
    private fun separation(a: Double, b: Double): Double =
        kotlin.math.abs(a - b).let { minOf(it, 360.0 - it) }

    /**
     * A bitmap [Palette] can actually sample. A `Config.HARDWARE` bitmap's pixels live
     * in graphics memory and cannot be read back — `getPixels` throws — so it is copied
     * to a software config. Any other config is returned as-is, with no copy: artwork is
     * multi-megabyte and the common case must not allocate a second one.
     *
     * Returns null only when the copy allocation fails.
     */
    internal fun toReadable(bitmap: Bitmap): Bitmap? =
        if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

    /** WCAG contrast ratio: `(L_lighter + 0.05) / (L_darker + 0.05)`. */
    internal fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** WCAG relative luminance, over gamma-expanded sRGB channels. */
    private fun relativeLuminance(color: Color): Double =
        0.2126 * expand(color.red) + 0.7152 * expand(color.green) + 0.0722 * expand(color.blue)

    private fun expand(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}
