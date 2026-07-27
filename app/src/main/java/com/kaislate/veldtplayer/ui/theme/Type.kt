package com.kaislate.veldtplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.kaislate.veldtplayer.R

/**
 * Bricolage Grotesque (OFL 1.1) is a VARIABLE font: one file serves every weight
 * and every optical size.
 *
 * Its `fvar` defaults sit at the axis extremes — `opsz` 96, `wght` 800, `wdth` 100
 * (the default instance is literally named "Bricolage Grotesque 96pt ExtraBold").
 * Every axis we care about must therefore be set EXPLICITLY; anything left alone
 * renders from the 96-point ExtraBold display master, which at list-row sizes reads
 * cramped and spindly. On API < 26 variation settings are ignored entirely and that
 * 96pt ExtraBold default is what renders — moot at minSdk 29, but the reason this
 * must not be relied on if the floor ever drops.
 *
 * [opticalSize] should track the size the text is actually rendered at.
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight, opticalSize: TextUnit) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.width(100f),
        FontVariation.opticalSizing(opticalSize),
    ),
)

/** All four weights we use, cut at a single optical size. */
private fun bricolageFamily(opticalSize: TextUnit) = FontFamily(
    bricolage(FontWeight.Normal, opticalSize),
    bricolage(FontWeight.Medium, opticalSize),
    bricolage(FontWeight.SemiBold, opticalSize),
    bricolage(FontWeight.Bold, opticalSize),
)

/**
 * Large-format face — screen titles and section headers, bound to the `display*`
 * and `headline*` slots. Never body text. Cut at `opsz` 48 for the ~24–57sp range
 * those slots occupy.
 */
val DisplayFamily = bricolageFamily(48.sp)

/**
 * Text-range face — list-row titles, track and album names, bound to `titleLarge`
 * and `titleMedium`. Cut at `opsz` 18: those slots render at 16–22sp, where the
 * display master's tight tracking and fine joints would read cramped.
 */
val TitleFamily = bricolageFamily(18.sp)

object VeldtText {
    /**
     * Tabular figures. Any digit that changes while on screen (playback position,
     * duration, counts) MUST use this, or the text width jitters every second.
     *
     * The 14sp size is DELIBERATE, not inherited. This style is normally passed
     * standalone (`style = VeldtText.numeric`), which REPLACES the surrounding
     * slot's style rather than merging with it, so an unset size would silently
     * resolve to Compose's default instead of the slot's. Callers that need a
     * larger readout should copy it with an explicit size.
     *
     * VERIFIED on device in Task 14 and the monospace fallback is NOT needed: ten
     * consecutive readings of the now-playing position (1:12 through 1:25) measured a
     * constant 42–43px ink box with the colon and the trailing digit landing in the same
     * pixel columns every time. That range is the decisive one — it contains `1`, which is
     * the glyph a proportional cut narrows and the reason a ticking clock jitters at all.
     * Bricolage Grotesque honours `tnum`.
     */
    val numeric = TextStyle(
        fontFamily = TitleFamily,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )
}

/**
 * Display slots use Bricolage; body/label slots stay on the platform sans so the
 * bulk of the UI feels native and the APK stays small (spec §10).
 */
val VeldtTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold),
        displayMedium = base.displayMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold),
        displaySmall = base.displaySmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium),
        titleLarge = base.titleLarge.copy(fontFamily = TitleFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = TitleFamily, fontWeight = FontWeight.Medium),
    )
}
