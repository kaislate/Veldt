// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

// Robolectric: PackageManager (install/remove) and the registered BroadcastReceiver.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackageManagerWispPresenceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `not installed by default`() {
        val presence = PackageManagerWispPresence(context)
        assertEquals(false, presence.installed.value)
    }

    @Test fun `reports installed when Wisp's package is already present at construction`() {
        installWisp()
        val presence = PackageManagerWispPresence(context)
        assertEquals(true, presence.installed.value)
    }

    @Test fun `an install broadcast for Wisp's package flips it to installed`() {
        val presence = PackageManagerWispPresence(context)
        assertEquals(false, presence.installed.value)
        installWisp()
        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, PackageManagerWispPresence.WISP_PACKAGE)
        assertEquals(true, presence.installed.value)
    }

    @Test fun `a remove broadcast for Wisp's package flips it back`() {
        installWisp()
        val presence = PackageManagerWispPresence(context)
        assertEquals(true, presence.installed.value)
        shadowOf(context.packageManager).removePackage(PackageManagerWispPresence.WISP_PACKAGE)
        sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED, PackageManagerWispPresence.WISP_PACKAGE)
        assertEquals(false, presence.installed.value)
    }

    /** The IntentFilter is scoped to Wisp's own package name, not a bare `scheme="package"`
     *  filter — see [PackageManagerWispPresence]'s KDoc for why that distinction matters. */
    @Test fun `a broadcast for an unrelated package is ignored`() {
        val presence = PackageManagerWispPresence(context)
        installWisp() // installed on disk, but no broadcast fired for Wisp itself
        sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, "com.example.other")
        assertEquals(false, presence.installed.value)
    }

    private fun installWisp() {
        val info = PackageInfo().apply { packageName = PackageManagerWispPresence.WISP_PACKAGE }
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun sendPackageBroadcast(action: String, packageName: String) {
        context.sendBroadcast(Intent(action, Uri.parse("package:$packageName")))
        ShadowLooper.idleMainLooper()
    }
}
