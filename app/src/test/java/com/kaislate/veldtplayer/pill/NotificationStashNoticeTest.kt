// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStashNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() =
        shadowOf(manager).getNotification(NotificationStashNotice.NOTIFICATION_ID)

    private fun tap() {
        context.sendBroadcast(Intent(NotificationStashNotice.ACTION_UNSTASH).setPackage(context.packageName))
        ShadowLooper.idleMainLooper()
    }

    @Test fun `show posts the notice and its tap runs the callback`() {
        var taps = 0
        val notice = NotificationStashNotice(context)
        notice.show { taps++ }
        assertNotNull(posted())
        tap()
        assertEquals(1, taps)
    }

    @Test fun `cancel removes the notice and stops listening`() {
        var taps = 0
        val notice = NotificationStashNotice(context)
        notice.show { taps++ }
        notice.cancel()
        assertNull(posted())
        tap()
        assertEquals(0, taps)
        notice.cancel() // idempotent
    }

    @Test fun `without the notification permission nothing is posted`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationStashNotice(context).show { }
        assertNull(posted())
    }
}
