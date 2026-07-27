package com.kaislate.veldtplayer.ui.theme

import android.graphics.Bitmap
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
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
 * Derives a per-track palette from album art.
 *
 * Original implementation, written from a behavioural specification — it is not
 * derived from any third-party source, so Veldt carries no attribution obligation
 * for this file.
 *
 * [extract] walks every pixel of the bitmap via [Palette]; it must never be called on
 * the main thread. Use `PaletteCache.paletteFor`, which dispatches it and memoises the
 * result. Callers must keep the bitmap alive and unrecycled across the call.
 *
 * [Palette] cannot sample a `Config.HARDWARE` bitmap, so [extract] converts one itself
 * before generating. It does NOT rely on callers passing `allowHardware(false)`: that
 * flag governs only Coil's own decoder, and `AlbumArtFetcher` returns a fully-formed
 * `DrawableResult` that bypasses the decoder entirely — so the flag would be a no-op on
 * the app's main art path. Guarding here covers every caller.
 */
object ColorExtractor {

    /** WCAG minimum contrast for large text and UI components. */
    private const val MIN_CONTRAST_RATIO = 3.0

    /**
     * Fraction of the remaining distance to white that one lightening step closes.
     * Fine enough that the result lands just past [MIN_CONTRAST_RATIO] instead of
     * overshooting into washed-out near-white.
     */
    private const val LIGHTEN_STEP = 0.08f

    /**
     * Hard bound on lightening iterations, so [ensureContrast] terminates even when the
     * requested contrast is unreachable (white on white). 24 steps close ~86% of the
     * distance to white — well beyond the worst case that is actually solvable.
     */
    private const val MAX_LIGHTEN_STEPS = 24

    /**
     * Luminance at which white-on-colour and black-on-colour contrast exactly, i.e.
     * `(1.05 / (L + 0.05)) == ((L + 0.05) / 0.05)`. Below it a light foreground wins.
     */
    private const val ON_BG_CROSSOVER = 0.179

    /** Index of saturation in [Palette.Swatch.getHsl]'s `{hue, saturation, lightness}`. */
    private const val HSL_SATURATION = 1

    /** Ceiling on scrub-bar gradient stops; mirrored by `PaletteSlots.SLOT_COUNT`. */
    private const val MAX_WAVE_COLORS = 5

    private val NEUTRAL_BG = Color(0xFF101014)
    private val NEUTRAL_ON_DARK = Color(0xFFF2F2F5)
    private val NEUTRAL_ON_LIGHT = Color(0xFF0B0B0D)
    private val NEUTRAL_ACCENT = Color(0xFF8A8A93)

    /** Shown before art loads and whenever there is none. */
    private val NEUTRAL = DominantColors(
        bg = NEUTRAL_BG,
        onBg = NEUTRAL_ON_DARK,
        accent = NEUTRAL_ACCENT,
        waveColors = emptyList(),
    )

    fun extract(bitmap: Bitmap?): DominantColors {
        if (bitmap == null) return NEUTRAL

        // copy() returns null if the allocation fails — degrade rather than crash.
        val readable = toReadable(bitmap) ?: return NEUTRAL

        // Filters cleared: Palette's defaults reject near-black and near-white swatches,
        // which leaves moody or monochrome artwork with no swatches at all.
        val palette = Palette.from(readable).clearFilters().generate()

        // A dark ground first, because the whole app sits on it. Every lookup carries a
        // default, so artwork with no swatch of a given kind degrades instead of failing.
        val bg = Color(palette.getDarkMutedColor(palette.getDarkVibrantColor(NEUTRAL_BG.toArgb())))
        val onBg = if (relativeLuminance(bg) < ON_BG_CROSSOVER) NEUTRAL_ON_DARK else NEUTRAL_ON_LIGHT

        // Most chromatic first: the head of this list is the accent, the top few are the
        // scrub-bar gradient.
        val byChroma = palette.swatches.sortedByDescending { it.hsl[HSL_SATURATION] }

        // Lifted off the ground, so an accent pulled from dark artwork stays legible.
        val accent = ensureContrast(
            fg = Color(byChroma.firstOrNull()?.rgb ?: NEUTRAL_ACCENT.toArgb()),
            bg = bg,
        )

        val waveColors = byChroma.map { Color(it.rgb) }.distinct().take(MAX_WAVE_COLORS)

        return DominantColors(bg = bg, onBg = onBg, accent = accent, waveColors = waveColors)
    }

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

    /**
     * Returns [fg] lightened toward white just far enough to read against [bg], or [fg]
     * itself when it already does. Alpha is preserved. If the requested contrast is
     * unreachable the loop still terminates and returns the lightest colour it reached.
     */
    fun ensureContrast(fg: Color, bg: Color): Color {
        if (contrastRatio(fg, bg) >= MIN_CONTRAST_RATIO) return fg

        var red = fg.red
        var green = fg.green
        var blue = fg.blue
        repeat(MAX_LIGHTEN_STEPS) {
            red += (1f - red) * LIGHTEN_STEP
            green += (1f - green) * LIGHTEN_STEP
            blue += (1f - blue) * LIGHTEN_STEP
            val lifted = Color(red, green, blue, fg.alpha)
            if (contrastRatio(lifted, bg) >= MIN_CONTRAST_RATIO) return lifted
        }
        return Color(red, green, blue, fg.alpha)
    }

    /** WCAG contrast ratio: `(L_lighter + 0.05) / (L_darker + 0.05)`. */
    private fun contrastRatio(a: Color, b: Color): Double {
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
