// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launch window's synchronous copy of the theme mode (finding 10).
 *
 * Its whole contract is two properties: it round-trips, and it can never fail a launch. The raw
 * file and key are written directly here, the same way `SettingsRepositoryTest` pins its keys,
 * because a rename would pass every round trip and still reset every installed user's launch
 * theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThemeModeMirrorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val raw = context.getSharedPreferences("veldt-theme-mirror", Context.MODE_PRIVATE)

    @Before fun setUp() {
        raw.edit().clear().commit()
    }

    @Test fun `each mode round-trips`() {
        val mirror = ThemeModeMirror(context)
        val readBack = ThemeMode.entries.map { mode -> mirror.write(mode); mode to mirror.read() }
        assertEquals(ThemeMode.entries.map { it to it }, readBack)
    }

    @Test fun `each mode is stored by name under theme_mode`() {
        val mirror = ThemeModeMirror(context)
        val stored = ThemeMode.entries.map { mode -> mirror.write(mode); raw.getString("theme_mode", null) }
        assertEquals(listOf("LIGHT", "DARK", "SYSTEM"), stored)
    }

    /** What every install sees on its first launch after the update that added the mirror. */
    @Test fun `a missing value reads as system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeModeMirror(context).read())
    }

    /**
     * Garbage in both shapes a SharedPreferences file can hold it: a string that names no mode,
     * and a value of the wrong TYPE under the key, which `getString` answers by throwing.
     * Asserted as one list so a failure shows which shape leaked through.
     */
    @Test fun `a garbage value reads as system, whatever its type`() {
        val mirror = ThemeModeMirror(context)
        val reads = listOf(
            raw.edit().putString("theme_mode", "PURPLE").commit().let { mirror.read() },
            raw.edit().putString("theme_mode", "light").commit().let { mirror.read() },
            raw.edit().putString("theme_mode", "").commit().let { mirror.read() },
            raw.edit().putInt("theme_mode", 1).commit().let { mirror.read() },
        )
        assertEquals(List(4) { ThemeMode.SYSTEM }, reads)
    }
}
