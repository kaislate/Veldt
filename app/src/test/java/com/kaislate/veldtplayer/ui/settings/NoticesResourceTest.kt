// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The assertion is that the text is IN THE BUILT ARTIFACT, not merely in the repo: OFL 1.1,
 * Apache-2.0 and LGPL-3.0 all expect their notices to accompany what is distributed, and a
 * repo file that never enters the APK satisfies none of them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticesResourceTest {
    @Test fun `the bundled notices name every third-party licence we ship under`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val text = ctx.resources.openRawResource(R.raw.notices).bufferedReader().readText()
        val required = listOf("SIL Open Font License", "Apache License", "eAlvaTag", "Bricolage")
        val missing = required.filterNot { text.contains(it, ignoreCase = true) }
        assertEquals("notices bundled in the APK are missing: $missing", emptyList<String>(), missing)
    }
}
