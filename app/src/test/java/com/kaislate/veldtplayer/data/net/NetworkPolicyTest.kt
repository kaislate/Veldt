// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The network policy is a MANIFEST fact, and the manifest is the thing that ships. Asserting
 * it through the built `PackageInfo` — rather than by reading the XML in the repo — is the
 * same reasoning as [com.kaislate.veldtplayer.ui.settings.NoticesResourceTest].
 *
 * Cleartext is asserted through [ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC] rather than
 * through `NetworkSecurityPolicy.isCleartextTrafficPermitted()`, because the latter has a
 * permissive default that would return true whether or not the manifest said anything —
 * an assertion that cannot fail is not an assertion. Step 3 proves this one can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetworkPolicyTest {

    private fun appInfo(): ApplicationInfo {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
    }

    @Test fun `the shipped manifest requests INTERNET`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val requested = ctx.packageManager
            .getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()
        assertTrue(
            "INTERNET missing from the built manifest; requested = $requested",
            "android.permission.INTERNET" in requested,
        )
    }

    @Test fun `the shipped manifest permits cleartext traffic`() {
        val flags = appInfo().flags
        assertTrue(
            "FLAG_USES_CLEARTEXT_TRAFFIC not set; a LAN http:// Navidrome would be " +
                "unreachable. flags=0x${flags.toString(16)}",
            flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
    }
}
