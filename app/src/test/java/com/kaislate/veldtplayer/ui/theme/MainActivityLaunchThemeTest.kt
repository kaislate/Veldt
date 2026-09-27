// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.MainActivity
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.data.settings.ThemeModeMirror
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Finding 10, at the only place it can be observed: the real `MainActivity`, created with the
 * launch mirror set, before anything is composed.
 *
 * Only `create()` is driven. The content view is set there, which is when the window's decor is
 * generated and styled, but nothing composes until the activity is attached and drawn, so the
 * nav host, its view models and Room are never reached.
 *
 * Three observations per launch, asserted together so a failure shows which one moved:
 *  - the activity theme's `isLightTheme`, which is what [LaunchTheme] set before
 *    `super.onCreate`;
 *  - whether the decor's background is a light colour, which is fixed when the content view is
 *    set and so shows the theme was in place BEFORE content, not merely at some point;
 *  - the per-app night mode handed to the system (API 31+, and this suite runs on 34).
 *
 * The configuration Robolectric starts with is not night, so a DARK result can only come from the
 * mirror and never from the system default. LIGHT is the case finding 10 is about, and it would
 * pass by accident against a light default, which is why DARK is asserted as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchThemeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SettingsRepository(context)
    private val mirror = ThemeModeMirror(context)
    private var controller: ActivityController<MainActivity>? = null

    private data class Launch(val lightTheme: Boolean, val lightDecor: Boolean, val nightMode: Int)

    @Before fun setUp() {
        runBlocking { settings.clearForTest() }
    }

    @After fun tearDown() {
        controller?.destroy()
    }

    /** Creates the activity and reads the three observations BEFORE the main looper runs again. */
    private fun create(): Launch {
        val c = Robolectric.buildActivity(MainActivity::class.java).also { controller = it }
        val activity = c.get()
        c.create()
        val attrs = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.isLightTheme))
        val lightTheme = attrs.getBoolean(0, false)
        attrs.recycle()
        val decor = activity.window.decorView.background as ColorDrawable
        val uiMode = activity.getSystemService(UiModeManager::class.java)
        return Launch(
            lightTheme = lightTheme,
            lightDecor = ColorUtils.calculateLuminance(decor.color) > 0.5,
            nightMode = shadowOf(uiMode).applicationNightMode,
        )
    }

    @Test fun `a LIGHT mirror launches light, before content`() {
        runBlocking { settings.setThemeMode(ThemeMode.LIGHT) }
        assertEquals(
            Launch(lightTheme = true, lightDecor = true, nightMode = UiModeManager.MODE_NIGHT_NO),
            create(),
        )
    }

    @Test fun `a DARK mirror launches dark, before content`() {
        runBlocking { settings.setThemeMode(ThemeMode.DARK) }
        assertEquals(
            Launch(lightTheme = false, lightDecor = false, nightMode = UiModeManager.MODE_NIGHT_YES),
            create(),
        )
    }

    /** SYSTEM leaves the window to DayNight (not night here) and tells the system to follow itself. */
    @Test fun `a SYSTEM mirror launches with the system, and says so to the system`() {
        runBlocking { settings.setThemeMode(ThemeMode.SYSTEM) }
        assertEquals(
            Launch(lightTheme = true, lightDecor = true, nightMode = UiModeManager.MODE_NIGHT_AUTO),
            create(),
        )
    }

    /**
     * The mirror is a cache. When it disagrees with DataStore, the launch is drawn from the mirror
     * (it is all there is before the first frame), and then DataStore's first emission repairs the
     * mirror and the system's night mode, so the NEXT launch is right.
     */
    @Test fun `a stale mirror draws this launch and is repaired from DataStore`() {
        runBlocking { settings.setThemeMode(ThemeMode.DARK) }
        mirror.write(ThemeMode.LIGHT)

        val launch = create()
        // DataStore reads on its own thread and delivers on the main looper; wait for it, bounded.
        var polls = 0
        while (mirror.read() != ThemeMode.DARK && polls++ < 200) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper()
        }
        val uiMode = context.getSystemService(UiModeManager::class.java)

        // The night mode seen at create() is not asserted: the repair may already have run by
        // then, which is a race in the test, not a property of the launch. The window's theme and
        // decor are fixed at create() and cannot be touched by it.
        assertEquals(
            listOf<Any>(true, true, ThemeMode.DARK, UiModeManager.MODE_NIGHT_YES),
            listOf<Any>(
                launch.lightTheme, launch.lightDecor,
                mirror.read(), shadowOf(uiMode).applicationNightMode,
            ),
        )
    }
}
