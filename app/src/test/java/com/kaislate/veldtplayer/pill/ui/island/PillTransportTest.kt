// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import android.media.session.PlaybackState
import com.kaislate.veldtplayer.pill.PillCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What each pill/card control sends through [PillCommands]. */
class PillTransportTest {

    private class Recording : PillCommands {
        val sent = mutableListOf<String>()
        override fun togglePlayPause() { sent += "toggle" }
        override fun next() { sent += "next" }
        override fun previous() { sent += "previous" }
        override fun seekTo(positionMs: Long) { sent += "seek:$positionMs" }
    }

    @Test fun `each button sends exactly its own command`() {
        val actual = PillButton.entries.associateWith { button ->
            Recording().also { PillTransport.press(button, it) }.sent
        }
        assertEquals(
            mapOf(
                PillButton.PREVIOUS to listOf("previous"),
                PillButton.PLAY_PAUSE to listOf("toggle"),
                PillButton.NEXT to listOf("next"),
            ),
            actual,
        )
    }

    @Test fun `a scrub lands at the fraction of the duration`() {
        assertEquals(listOf(0L, 60_000L, 240_000L), listOf(0f, 0.25f, 1f).map { PillTransport.seekTargetMs(it, 240_000L) })
    }

    @Test fun `a scrub past either end is clamped to the track`() {
        assertEquals(listOf(0L, 240_000L), listOf(-0.3f, 1.4f).map { PillTransport.seekTargetMs(it, 240_000L) })
    }

    @Test fun `no known duration means no seek at all`() {
        assertNull(PillTransport.seekTargetMs(0.5f, 0L))
        assertNull(PillTransport.seekTargetMs(0.5f, -1L))
        val r = Recording()
        PillTransport.seek(0.5f, 0L, r)
        assertEquals(emptyList<String>(), r.sent)
    }

    @Test fun `seek sends seekTo with the target`() {
        val r = Recording()
        PillTransport.seek(0.5f, 200_000L, r)
        assertEquals(listOf("seek:100000"), r.sent)
    }

    @Test fun `skip buttons follow the advertised actions, null meaning trust the session`() {
        val both = PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        val playOnly = PlaybackState.ACTION_PLAY_PAUSE
        assertEquals(
            listOf(true, true, true, true, false, false, false, true),
            listOf(
                PillTransport.canSkipPrevious(null), PillTransport.canSkipNext(null),
                PillTransport.canSkipPrevious(both), PillTransport.canSkipNext(both),
                PillTransport.canSkipPrevious(playOnly), PillTransport.canSkipNext(playOnly),
                PillTransport.canSkipPrevious(PlaybackState.ACTION_SKIP_TO_NEXT),
                PillTransport.canSkipNext(PlaybackState.ACTION_SKIP_TO_NEXT),
            ),
        )
    }
}
