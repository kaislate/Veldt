// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.sleep

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sleep timer's wire format, both ways. Robolectric only for a real [Bundle]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepTimerCommandsTest {

    private fun parse(action: String, args: Bundle = Bundle.EMPTY) = SleepTimerCommands.parse(action, args)

    @Test fun `set carries minutes, and only 1 to 180 of them`() {
        assertEquals(SleepTimerRequest.SetMinutes(1), parse(SleepTimerCommands.ACTION_SET, SleepTimerCommands.minutesArgs(1)))
        assertEquals(SleepTimerRequest.SetMinutes(180), parse(SleepTimerCommands.ACTION_SET, SleepTimerCommands.minutesArgs(180)))
        assertNull(parse(SleepTimerCommands.ACTION_SET, SleepTimerCommands.minutesArgs(0)))
        assertNull(parse(SleepTimerCommands.ACTION_SET, SleepTimerCommands.minutesArgs(181)))
        assertNull("no argument at all", parse(SleepTimerCommands.ACTION_SET))
    }

    @Test fun `set with end of track, extend, cancel, and anything else`() {
        assertEquals(SleepTimerRequest.SetEndOfTrack, parse(SleepTimerCommands.ACTION_SET, SleepTimerCommands.endOfTrackArgs()))
        assertEquals(SleepTimerRequest.Extend, parse(SleepTimerCommands.ACTION_EXTEND))
        assertEquals(SleepTimerRequest.Cancel, parse(SleepTimerCommands.ACTION_CANCEL))
        assertNull(parse("com.example.SOMETHING_ELSE"))
    }

    @Test fun `the three session commands carry the three actions`() {
        assertEquals(
            listOf(SleepTimerCommands.ACTION_SET, SleepTimerCommands.ACTION_EXTEND, SleepTimerCommands.ACTION_CANCEL),
            listOf(SleepTimerCommands.SET, SleepTimerCommands.EXTEND, SleepTimerCommands.CANCEL).map { it.customAction },
        )
    }

    @Test fun `state survives the session extras round trip`() {
        listOf(SleepTimerState.Off, SleepTimerState.EndOfTrack, SleepTimerState.Timed(123_456_789L)).forEach {
            assertEquals(it, SleepTimerCommands.fromExtras(SleepTimerCommands.toExtras(it)))
        }
    }

    @Test fun `missing or unreadable extras read as off`() {
        assertEquals(SleepTimerState.Off, SleepTimerCommands.fromExtras(null))
        assertEquals(SleepTimerState.Off, SleepTimerCommands.fromExtras(Bundle.EMPTY))
        val noEnd = Bundle().apply { putString("com.kaislate.veldtplayer.sleep.MODE", "timed") }
        assertEquals(SleepTimerState.Off, SleepTimerCommands.fromExtras(noEnd))
    }

    @Test fun `the notification label says the minutes left and what a tap does`() {
        assertEquals(
            "Sleep in 24 min · tap to cancel",
            SleepTimerCommands.notificationLabel(SleepTimerStatus(SleepTimerState.Timed(0), 24)),
        )
        assertEquals(
            "Sleep at end of track · tap to cancel",
            SleepTimerCommands.notificationLabel(SleepTimerStatus(SleepTimerState.EndOfTrack, null)),
        )
        assertNull(SleepTimerCommands.notificationLabel(SleepTimerStatus(SleepTimerState.Off, null)))
    }
}
