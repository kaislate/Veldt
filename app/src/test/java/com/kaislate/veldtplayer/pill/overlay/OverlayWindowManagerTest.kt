// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PillCommands
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayWindowManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private object NoCommands : PillCommands {
        override fun togglePlayPause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }

    @Test fun `a missing permission declines quietly - it is not an Android refusal`() {
        val manager = OverlayWindowManager(
            context,
            SettingsRepository(context),
            object : OverlayPermission { override fun isGranted() = false },
            NoCommands,
        )
        manager.showIsland()
        assertNull("Settings must point at the grant row, not say 'Android refused'", manager.showFailure.value)
        manager.hide() // nothing attached: a no-op, not a throw
    }
}
