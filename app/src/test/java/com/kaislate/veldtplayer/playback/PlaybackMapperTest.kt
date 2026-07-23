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
}
