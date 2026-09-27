// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMapperTest {
    // Media3 Player state ints: 1 IDLE, 2 BUFFERING, 3 READY, 4 ENDED.
    @Test fun readyAndPlaying_isPlaying() =
        assertEquals(PlayState.PLAYING, PlaybackMapper.playState(3, true))

    @Test fun readyNotPlaying_isPaused() =
        assertEquals(PlayState.PAUSED, PlaybackMapper.playState(3, false))

    @Test fun buffering_isBuffering() =
        assertEquals(PlayState.BUFFERING, PlaybackMapper.playState(2, true))

    @Test fun ended_isEnded() =
        assertEquals(PlayState.ENDED, PlaybackMapper.playState(4, false))

    @Test fun idle_isIdle() =
        assertEquals(PlayState.IDLE, PlaybackMapper.playState(1, false))

    // The restored queue (spec §3): loaded, unprepared, no error — playable with one tap.
    @Test fun idleLoadedWithoutError_isPaused() =
        assertEquals(PlayState.PAUSED, PlaybackMapper.playState(1, false, loadedWithoutError = true))

    // The skip-on bound / a network pause: the error is what keeps it IDLE (isStalled).
    @Test fun idleWithError_staysIdle() =
        assertEquals(PlayState.IDLE, PlaybackMapper.playState(1, false, loadedWithoutError = false))

    @Test fun loadedWithoutError_changesNothingButIdle() {
        assertEquals(PlayState.PLAYING, PlaybackMapper.playState(3, true, loadedWithoutError = true))
        assertEquals(PlayState.BUFFERING, PlaybackMapper.playState(2, true, loadedWithoutError = true))
        assertEquals(PlayState.ENDED, PlaybackMapper.playState(4, false, loadedWithoutError = true))
    }
}
