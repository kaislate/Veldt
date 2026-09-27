// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import android.content.Context

/**
 * A synchronous copy of [SettingsRepository.themeMode], for the one reader that cannot wait for
 * DataStore: `MainActivity` before `super.onCreate`, choosing the theme its window is drawn with
 * (finding 10).
 *
 * DataStore stays the source of truth. It can only be read asynchronously, and the window's theme
 * has to be settled before the first frame, so the first frame used to be drawn from a pinned
 * dark theme, which a Light user saw as a dark flash for about 450 ms on an S21 FE. This is a
 * CACHE of it in [android.content.SharedPreferences], whose reads are synchronous once the file is
 * loaded. Written in two places: [SettingsRepository.setThemeMode], alongside DataStore, and
 * `MainActivity` on every DataStore emission, which repairs a mirror that went missing or fell
 * behind.
 *
 * Because it is only a cache, reading it never fails: a missing, unrecognised or wrongly-typed
 * value reads as [ThemeMode.SYSTEM]. The worst a bad mirror can do is draw one launch with the
 * system's theme, which is what every launch did before the mirror existed. The value is stored
 * by enum NAME for the same reason [SettingsRepository] stores it that way; see its KDoc.
 *
 * Its own file rather than a key in DataStore's: DataStore owns its file, and a second writer
 * there is not supported.
 */
class ThemeModeMirror(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(): ThemeMode {
        // getString throws ClassCastException for a non-string value under the key. A corrupt
        // cache must not stop the app from starting, so any failure is SYSTEM.
        val stored = runCatching { prefs.getString(KEY, null) }.getOrNull()
        return ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.SYSTEM
    }

    /**
     * `apply`, not `commit`: the in-memory value changes immediately, so a read later in this
     * process sees it, and the disk write happens off the caller's thread (the framework waits
     * for it when an activity stops). A write lost to a hard kill is repaired from DataStore on
     * the next launch.
     */
    fun write(mode: ThemeMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    internal companion object {
        /** The SharedPreferences file. Pinned by `ThemeModeMirrorTest`: renaming it resets every
         *  installed user's launch theme to SYSTEM until their next DataStore emission. */
        const val FILE = "veldt-theme-mirror"
        const val KEY = "theme_mode"
    }
}
