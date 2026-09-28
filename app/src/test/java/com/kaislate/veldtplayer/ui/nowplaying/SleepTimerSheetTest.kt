// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.kaislate.veldtplayer.playback.sleep.SleepTimerState
import com.kaislate.veldtplayer.ui.theme.DominantColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sleep sheet's content (spec §4): which options it offers in each state and what each one
 * sends. The sheet wrapper is `ModalBottomSheet` plus dismissal; the decisions are all here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerSheetTest {

    @get:Rule val compose = createComposeRule()

    private val palette = DominantColors(
        bg = Color(0xFF202020),
        onBg = Color(0xFFF0F0F0),
        onBgSecondary = Color(0xFFC8C8C8),
        accent = Color(0xFF8AB4F8),
    )

    private val calls = mutableListOf<String>()

    private fun show(state: SleepTimerState, remaining: String? = null) {
        compose.setContent {
            SleepTimerOptions(
                state = state,
                remaining = remaining,
                palette = palette,
                onSetMinutes = { calls += "set:$it" },
                onSetEndOfTrack = { calls += "end" },
                onExtend = { calls += "extend" },
                onCancel = { calls += "cancel" },
            )
        }
    }

    private fun shown(vararg texts: String) = texts.filter {
        compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun `off offers the presets, end of track and a custom length, and nothing to extend or cancel`() {
        show(SleepTimerState.Off)
        assertEquals(
            listOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this track", "Custom: 30 min", "Start"),
            shown(
                "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this track",
                "Custom: 30 min", "Start", "+10 min", "Cancel timer",
            ),
        )
    }

    @Test fun `each preset and end of track send what they say`() {
        show(SleepTimerState.Off)
        listOf("15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this track").forEach {
            compose.onNodeWithText(it).performClick()
        }
        assertEquals(listOf("set:15", "set:30", "set:45", "set:60", "end"), calls)
    }

    @Test fun `the custom slider runs 1 to 180 minutes and Start sends the chosen length`() {
        show(SleepTimerState.Off)
        val slider = compose.onNodeWithContentDescription("Custom sleep timer length")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(95f) }
        compose.onNodeWithText("Custom: 95 min").assertExists()
        compose.onNodeWithText("Start").performClick()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.onNodeWithText("Start").performClick()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(180f) }
        compose.onNodeWithText("Start").performClick()
        assertEquals(listOf("set:95", "set:1", "set:180"), calls)
    }

    @Test fun `a running timer shows its time left with +10 min and cancel`() {
        show(SleepTimerState.Timed(0), remaining = "12:34")
        compose.onNodeWithText("Pausing in 12:34").assertExists()
        compose.onNodeWithText("+10 min").performClick()
        compose.onNodeWithText("Cancel timer").performClick()
        assertEquals(listOf("extend", "cancel"), calls)
    }

    @Test fun `an end of track timer says so`() {
        show(SleepTimerState.EndOfTrack, remaining = "1:05")
        compose.onNodeWithText("Pausing at the end of this track (1:05)").assertExists()
    }

    // ---- the time left beside the bed ----

    @Test fun `remaining time for each state`() {
        assertEquals(null, sleepRemainingMs(SleepTimerState.Off, 0, 0, 0))
        assertEquals(90_000L, sleepRemainingMs(SleepTimerState.Timed(100_000), 10_000, 0, 0))
        assertEquals("never negative", 0L, sleepRemainingMs(SleepTimerState.Timed(100_000), 200_000, 0, 0))
        assertEquals(65_000L, sleepRemainingMs(SleepTimerState.EndOfTrack, 0, 135_000, 200_000))
        assertEquals("unknown duration", null, sleepRemainingMs(SleepTimerState.EndOfTrack, 0, 0, 0))
    }

    @Test fun `remaining time formats as m-ss, or h-mm-ss past the hour, rounded up`() {
        assertEquals("0:00", formatSleepRemaining(0))
        assertEquals("0:01", formatSleepRemaining(1))
        assertEquals("4:05", formatSleepRemaining(245_000))
        assertEquals("15:00", formatSleepRemaining(899_001))
        assertEquals("1:02:03", formatSleepRemaining(3_723_000))
        assertEquals("3:00:00", formatSleepRemaining(180 * 60_000L))
    }
}
