// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The pill card's "open Veldt at now-playing" intent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NowPlayingDeepLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `the intent targets MainActivity and carries the request`() {
        val i = NowPlayingDeepLink.intent(context)
        assertEquals(MainActivity::class.java.name, i.component?.className)
        assertTrue(NowPlayingDeepLink.isRequest(i))
    }

    @Test fun `the intent can start from a non-Activity context and reuses the existing task`() {
        val flags = NowPlayingDeepLink.intent(context).flags
        val wanted = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(wanted, flags and wanted)
    }

    @Test fun `an ordinary launch is not a request`() {
        val launcher = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        assertFalse(NowPlayingDeepLink.isRequest(launcher))
        assertFalse(NowPlayingDeepLink.isRequest(null))
        assertFalse(
            NowPlayingDeepLink.isRequest(Intent().putExtra(NowPlayingDeepLink.EXTRA_OPEN_NOW_PLAYING, false)),
        )
    }
}
