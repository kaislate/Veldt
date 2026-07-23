package com.kaislate.veldtplayer.playback

/** Pure, framework-free translation of Media3 player state to our [PlayState].
 *  Media3 Player.STATE_* ints are passed in so this stays JVM-unit-testable. */
object PlaybackMapper {
    private const val STATE_IDLE = 1
    private const val STATE_BUFFERING = 2
    private const val STATE_READY = 3
    private const val STATE_ENDED = 4

    fun playState(playbackState: Int, playWhenReady: Boolean): PlayState = when (playbackState) {
        STATE_BUFFERING -> PlayState.BUFFERING
        STATE_ENDED -> PlayState.ENDED
        STATE_IDLE -> PlayState.IDLE
        STATE_READY -> if (playWhenReady) PlayState.PLAYING else PlayState.PAUSED
        else -> PlayState.IDLE
    }
}
