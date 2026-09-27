// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM: the `PlaybackState.STATE_*` values are compile-time constants. */
class WidgetStateTest {

    @Test fun `nothing published, or blank title and artist, is the empty state`() {
        assertEquals(WidgetState.Empty, WidgetState.of(null, null, null))
        assertEquals(WidgetState.Empty, WidgetState.of("", "", PlaybackState.STATE_NONE))
        assertEquals(WidgetState.Empty, WidgetState.of("  ", null, PlaybackState.STATE_PAUSED))
    }

    @Test fun `a title or an artist alone is a track`() {
        assertEquals(WidgetState.Track("Song", "", false), WidgetState.of("Song", "", PlaybackState.STATE_PAUSED))
        assertEquals(WidgetState.Track("", "Artist", false), WidgetState.of(null, "Artist", null))
    }

    @Test fun `playing and buffering show pause, everything else shows play`() {
        val table = mapOf(
            PlaybackState.STATE_PLAYING to true,
            PlaybackState.STATE_BUFFERING to true,
            PlaybackState.STATE_PAUSED to false,
            PlaybackState.STATE_STOPPED to false,
            PlaybackState.STATE_NONE to false,
            null to false,
        )
        for ((state, playing) in table) {
            assertEquals("state $state", WidgetState.Track("t", "a", playing), WidgetState.of("t", "a", state))
        }
    }
}
