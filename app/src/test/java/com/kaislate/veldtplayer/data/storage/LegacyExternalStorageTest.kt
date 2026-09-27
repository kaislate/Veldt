// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.storage

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `android:requestLegacyExternalStorage` is a MANIFEST fact, and the manifest is the thing that
 * ships — same reasoning as [com.kaislate.veldtplayer.data.net.NetworkPolicyTest]: asserted
 * through the built `ApplicationInfo`, not by re-reading the XML in the repo.
 *
 * Measured on an LG G7 (API 29, Veldt's own uid): `Environment.isExternalStorageLegacy()` was
 * false and every file-path read of shared storage (the .mp3s, a sidecar .lrc) hit `EACCES`
 * while MediaStore content-uri reads succeeded. This flag flipped `isExternalStorageLegacy()` to
 * true and made all three readable by path on the same device. It has effect only on API 29 —
 * the manifest fact pinned here is unconditional, and it is the platform, not this app, that
 * ignores it from API 30 with `targetSdk >= 30`.
 *
 * The bit this reads — `ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE`, on the
 * `privateFlags` field — is `@hide` and absent from the public SDK stub this module compiles
 * against (unlike `FLAG_USES_CLEARTEXT_TRAFFIC` in [com.kaislate.veldtplayer.data.net.NetworkPolicyTest],
 * which is public), so it is read reflectively rather than by name. It is the real field on the
 * real framework `ApplicationInfo` class that Robolectric's manifest parsing populates — the same
 * parser [com.kaislate.veldtplayer.data.net.NetworkPolicyTest] already trusts for
 * `FLAG_USES_CLEARTEXT_TRAFFIC` — so a manifest without the attribute reads this as 0, and the
 * assertion can fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyExternalStorageTest {

    @Test fun `the shipped manifest requests legacy external storage`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val appInfo = ctx.packageManager.getApplicationInfo(ctx.packageName, 0)
        val requestLegacyExternalStorageBit =
            ApplicationInfo::class.java.getField("PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE")
                .getInt(null)
        val privateFlags = ApplicationInfo::class.java.getField("privateFlags").getInt(appInfo)
        assertTrue(
            "PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE not set; on API 29 (measured: LG G7) " +
                "every file-path read of shared storage (tags, embedded lyrics, sidecar .lrc) " +
                "fails with EACCES without it. privateFlags=0x${privateFlags.toString(16)}",
            privateFlags and requestLegacyExternalStorageBit != 0,
        )
    }
}
