// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings "Playback" section (0.9.2 spec §5): the ReplayGain mode and its pre-amp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackSectionTest {

    @get:Rule val compose = createComposeRule()

    private val modes = mutableListOf<ReplayGainMode>()
    private val preamps = mutableListOf<Int>()
    private val mode = mutableStateOf(ReplayGainMode.AUTO)

    private fun show(preamp: Int = 0) {
        compose.setContent {
            Column {
                PlaybackSection(
                    mode = mode.value,
                    preampDb = preamp,
                    onModeChange = { modes += it },
                    onPreampChange = { preamps += it },
                )
            }
        }
    }

    private val autoLabel = "Auto (album gain when playing an album in order)"

    @Test fun `the four modes are offered, the current one selected, and each sends itself`() {
        show()
        compose.onNodeWithText(autoLabel).assertIsSelected()
        listOf("Track gain", "Album gain", "Off", autoLabel).forEach { compose.onNodeWithText(it).performClick() }
        assertEquals(
            listOf(ReplayGainMode.TRACK, ReplayGainMode.ALBUM, ReplayGainMode.OFF, ReplayGainMode.AUTO),
            modes,
        )
    }

    @Test fun `the pre-amp shows its value and sends whole dB`() {
        show(preamp = 3)
        compose.onNodeWithText("+3 dB").assertExists()
        val slider = compose.onNodeWithContentDescription("ReplayGain pre-amp")
        slider.assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(-2f) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        assertEquals(listOf(-2, 6), preamps)
    }

    @Test fun `with ReplayGain off the pre-amp is kept but disabled`() {
        mode.value = ReplayGainMode.OFF
        show(preamp = -4)
        compose.onNodeWithText("−4 dB").assertExists()
        compose.onNodeWithContentDescription("ReplayGain pre-amp").assertIsNotEnabled()
    }

    @Test fun `pre-amp labels`() {
        assertEquals("+6 dB", formatPreamp(6))
        assertEquals("0 dB", formatPreamp(0))
        assertEquals("−6 dB", formatPreamp(-6))
    }
}
