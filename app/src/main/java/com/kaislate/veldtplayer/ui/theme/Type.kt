package com.kaislate.veldtplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.kaislate.veldtplayer.R

/**
 * Bricolage Grotesque (OFL 1.1) is a VARIABLE font: one file serves every weight.
 * [FontVariation.Settings] selects the axis values per weight; on API < 26 the
 * settings are ignored and the default instance renders, which is acceptable
 * (minSdk is 29, so this only matters if the floor ever drops).
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: FontWeight) = Font(
    resId = R.font.bricolage_grotesque,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.width(100f),
    ),
)

/** Display face — screen titles, track titles, section headers. Never body text. */
val DisplayFamily = FontFamily(
    bricolage(FontWeight.Normal),
    bricolage(FontWeight.Medium),
    bricolage(FontWeight.SemiBold),
    bricolage(FontWeight.Bold),
)

object VeldtText {
    /**
     * Tabular figures. Any digit that changes while on screen (playback position,
     * duration, counts) MUST use this, or the text width jitters every second.
     * Verified on-device in Task 14; if Bricolage lacks `tnum`, switch this style's
     * fontFamily to FontFamily.Monospace rather than shipping jittering digits.
     */
    val numeric = TextStyle(
        fontFamily = DisplayFamily,
        fontFeatureSettings = "tnum",
        fontWeight = FontWeight.Medium,
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
        titleLarge = base.titleLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Medium),
    )
}
