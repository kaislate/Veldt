// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.kaislate.veldtplayer.data.settings.ThemeMode

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

/** True when the app is painting a LIGHT ground. Read by [neutralPalette] and by any surface
 *  that must derive an art palette; provided once, by [VeldtTheme]. */
val LocalIsLightTheme = staticCompositionLocalOf { false }

/**
 * Which ground [mode] resolves to. A pure function, not an inline `when` in the composable,
 * because it is a DECISION and decisions belong in tested functions (GC 10) — and because a
 * `when` inside `@Composable` is unreachable without Compose UI test infrastructure.
 */
internal fun resolveDark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

/**
 * Veldt follows [mode]: Light, Dark, or the system.
 *
 * This used to force `darkColorScheme()`, because every colour the app painted came from a
 * pipeline with one branch and a light Material scaffold underneath it would have produced a
 * near-black mini-player welded to a white list. That is no longer true: [ArtSeed.colors]
 * derives a ground for either theme by solving for a contrast ratio, so both the Material
 * surfaces and the painted ones agree.
 */
@Composable
fun VeldtTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = resolveDark(mode, systemDark = isSystemInDarkTheme())
    CompositionLocalProvider(LocalIsLightTheme provides !dark) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
            typography = VeldtTypography,
            content = content,
        )
    }
}

/** The art-less palette every browse surface uses, derived for the CURRENT theme. */
@Composable
fun neutralPalette(): DominantColors = ArtSeed.NEUTRAL.colors(isLight = LocalIsLightTheme.current)
