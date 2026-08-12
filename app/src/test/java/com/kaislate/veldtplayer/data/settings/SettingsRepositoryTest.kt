// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: DataStore writes to a real file under the app's context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before fun setUp() {
        repo = SettingsRepository(ApplicationProvider.getApplicationContext())
    }

    @Test fun `the default is follow-system`() = runTest {
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test fun `a written mode reads back`() = runTest {
        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
    }

    /**
     * Stored by NAME, not ordinal. Reordering the enum must not silently change a user's
     * setting — with ordinals, inserting a value at the front turns everyone's DARK into
     * something else on upgrade, with no error anywhere.
     */
    @Test fun `an unrecognised stored value degrades to the default`() = runTest {
        repo.writeRawForTest("NOT_A_MODE")
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test fun `each mode round-trips under its own name`() = runTest {
        val readBack = ThemeMode.entries.map { mode ->
            repo.setThemeMode(mode); mode to repo.themeMode.first()
        }
        assertEquals(ThemeMode.entries.map { it to it }, readBack)
    }
}
