package com.kaislate.veldtplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

@Composable
fun VeldtTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, typography = VeldtTypography, content = content)
}
