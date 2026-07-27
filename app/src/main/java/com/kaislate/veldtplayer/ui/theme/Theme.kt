package com.kaislate.veldtplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Opacity of the app's bottom chrome — the navigation bar and the mini-player sitting on it.
 *
 * High enough that the labels never fight the artwork scrolling under them, low enough that
 * something is visibly passing behind: at full opacity the bar reads as a wall rather than as
 * a layer. ONE constant because the two pieces are stacked, and two opacities there read as
 * two slabs instead of one pane.
 *
 * Lives with the theme rather than with either consumer: it is a surface property of the app,
 * and putting it next to the navigation bar made a generic `ui/components` composable import
 * from `ui/nav` to get at it.
 */
const val CHROME_ALPHA = 0.94f

/**
 * **Veldt is a dark app in this phase, and this is where that is decided.**
 *
 * Not an oversight and not a placeholder: every colour the app actually paints with comes from
 * [ColorExtractor], and that pipeline has one branch. `bg` is
 * `getDarkMutedColor(getDarkVibrantColor(...))` — a DARK swatch by construction — the neutral
 * fallback is `#101014`, and `onBg` is chosen by measuring the luminance of that dark ground.
 * There is no light palette to switch to, so following `isSystemInDarkTheme()` would not give a
 * light app; it would give a WHITE Material scaffold with the extracted dark palette still
 * painted on top of it — a near-black mini-player welded to the bottom of a white list, and an
 * art-less thumbnail as a black box in every row. (That was the shipped behaviour: this file
 * landed in the first task and was never revisited once the palette arrived.)
 *
 * `darkColorScheme()` is therefore forced, so the Material surfaces the app does not repaint
 * (the scaffold ground, ripples, the snackbar) agree with the palette that covers them instead
 * of contradicting it. The system bars are told the same thing in `MainActivity`, and the
 * pre-Compose window background in `themes.xml` is pinned dark for the same reason — a
 * DayNight parent would flash white on a cold start on a light-mode phone.
 *
 * A real light palette is a colour-pipeline change (a second branch in [ColorExtractor], a
 * light `onBg` crossover, a light chrome alpha), not a theme change. When that exists, this
 * is the line that starts following the system again.
 */
@Composable
fun VeldtTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), typography = VeldtTypography, content = content)
}
