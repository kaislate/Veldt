// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.MainActivity
import com.kaislate.veldtplayer.playback.PlaybackService
import com.kaislate.veldtplayer.ui.nav.NowPlayingDeepLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric for real [PendingIntent]s: each button's kind (service vs foreground service vs
 * activity), request code, flags, and the intent it carries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetIntentsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = ComponentName(context, PlaybackService::class.java)

    @Suppress("DEPRECATION")
    private fun keyEventOf(intent: Intent): KeyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)!!

    /** Asserts [pi] is a media-button press of [keyCode] addressed to [PlaybackService]. */
    private fun assertMediaButton(pi: PendingIntent, keyCode: Int, foreground: Boolean) {
        val shadow = shadowOf(pi)
        assertEquals("foreground-service start", foreground, shadow.isForegroundServiceIntent)
        assertEquals("plain service start", !foreground, shadow.isServiceIntent)
        assertEquals("request code is the key code", keyCode, shadow.requestCode)
        assertTrue("immutable", shadow.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        val intent = shadow.savedIntent
        assertEquals(Intent.ACTION_MEDIA_BUTTON, intent.action)
        assertEquals(service, intent.component)
        val event = keyEventOf(intent)
        assertEquals(KeyEvent.ACTION_DOWN, event.action)
        assertEquals(keyCode, event.keyCode)
    }

    @Test fun `play, when paused, is a foreground-service start of KEYCODE_MEDIA_PLAY`() {
        assertMediaButton(WidgetIntents.playPause(context, isPlaying = false), KeyEvent.KEYCODE_MEDIA_PLAY, foreground = true)
    }

    @Test fun `pause, when playing, is a plain service start of KEYCODE_MEDIA_PAUSE`() {
        assertMediaButton(WidgetIntents.playPause(context, isPlaying = true), KeyEvent.KEYCODE_MEDIA_PAUSE, foreground = false)
    }

    @Test fun `previous and next are plain service starts of their key codes`() {
        assertMediaButton(WidgetIntents.previous(context), KeyEvent.KEYCODE_MEDIA_PREVIOUS, foreground = false)
        assertMediaButton(WidgetIntents.next(context), KeyEvent.KEYCODE_MEDIA_NEXT, foreground = false)
    }

    @Test fun `the four buttons are four distinct PendingIntents`() {
        val all = listOf(
            WidgetIntents.playPause(context, false),
            WidgetIntents.playPause(context, true),
            WidgetIntents.previous(context),
            WidgetIntents.next(context),
        )
        assertEquals(4, all.toSet().size)
    }

    @Test fun `art and text open now playing through the pill's deep link`() {
        val shadow = shadowOf(WidgetIntents.openNowPlaying(context))
        assertTrue(shadow.isActivityIntent)
        assertEquals(WidgetIntents.REQUEST_NOW_PLAYING, shadow.requestCode)
        val intent = shadow.savedIntent
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertTrue(NowPlayingDeepLink.isRequest(intent))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `the empty state opens the app, without asking for now playing`() {
        val shadow = shadowOf(WidgetIntents.openApp(context))
        assertTrue(shadow.isActivityIntent)
        assertEquals(WidgetIntents.REQUEST_OPEN_APP, shadow.requestCode)
        val intent = shadow.savedIntent
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertFalse(NowPlayingDeepLink.isRequest(intent))
    }
}
