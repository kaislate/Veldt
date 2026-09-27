// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import androidx.annotation.StyleRes
import com.kaislate.veldtplayer.R
import com.kaislate.veldtplayer.data.settings.ThemeMode

/**
 * The theme the WINDOW is drawn with, before Compose paints anything (finding 10).
 *
 * Two different windows are involved, and each needs its own mechanism.
 *
 * 1. **The system's starting window**, shown on a cold start before this process has run any
 *    code at all. Nothing the app does at launch time can reach it. It is drawn from the
 *    manifest theme (`Theme.Veldt`, a DayNight theme) under whatever night mode the SYSTEM
 *    holds for this app. On API 31+ [UiModeManager.setApplicationNightMode] sets and persists
 *    that per-app night mode, which is why [tellSystem] runs whenever the choice changes, not
 *    only at launch. The next cold start's first frame then already matches. Below API 31 there
 *    is no per-app night mode. The starting window follows the system, and that is as far as it
 *    can be made to follow anything.
 *
 * 2. **The activity's own window**, whose background shows until the first composition. It is
 *    styled from the activity's theme at the moment its decor is created, and [applyBeforeCreate]
 *    sets that theme from [ThemeMode] explicitly. This works on every API level, and on 31+ it
 *    also covers the one launch after a change that [tellSystem] could not report in time.
 *
 * `AppCompatDelegate.setDefaultNightMode` is not used, although appcompat is on the classpath:
 * it only reaches AppCompat activities, and `MainActivity` is a `ComponentActivity`.
 *
 * [ThemeMode.SYSTEM] maps to the DayNight theme and to "follow the system", so a user who never
 * chose sees exactly the system's theme at every stage.
 */
internal object LaunchTheme {

    /** The activity theme for [mode]. `Theme.Veldt` is the manifest's DayNight theme. */
    @StyleRes
    fun styleFor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> R.style.Theme_Veldt_Light
        ThemeMode.DARK -> R.style.Theme_Veldt_Dark
        ThemeMode.SYSTEM -> R.style.Theme_Veldt
    }

    /** The per-app night mode [UiModeManager.setApplicationNightMode] takes for [mode]. */
    fun nightModeFor(mode: ThemeMode): Int = when (mode) {
        ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        // MODE_NIGHT_AUTO is "follow the system" for an application night mode.
        ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
    }

    /**
     * Must run BEFORE `super.onCreate`. Setting a theme after the window's decor exists changes
     * what later reads of the theme see, but never the window that is already styled.
     */
    fun applyBeforeCreate(activity: Activity, mode: ThemeMode) {
        activity.setTheme(styleFor(mode))
        tellSystem(activity, mode)
    }

    /**
     * Hands [mode] to the system, for the starting window of the NEXT cold start (API 31+ only;
     * see the object KDoc). Setting the value it already holds changes nothing, so calling this on
     * every launch and every DataStore emission is safe.
     *
     * On a real change the system updates this app's configuration while it runs. `MainActivity`
     * declares `uiMode` in its `configChanges` so that arrives as a configuration change, which
     * Compose absorbs, rather than as a relaunch.
     */
    fun tellSystem(context: Context, mode: ThemeMode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(UiModeManager::class.java)
                ?.setApplicationNightMode(nightModeFor(mode))
        }
    }
}
