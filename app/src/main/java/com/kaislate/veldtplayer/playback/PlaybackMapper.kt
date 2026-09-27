// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /**
     * [playState], except that an IDLE player which is merely NOT PREPARED yet reads as PAUSED.
     *
     * [loadedWithoutError] is "the player holds items and has no error". That is the restored
     * queue (spec §3), deliberately left unprepared until the user presses play — and a press does
     * work, because Media3 prepares an idle player on play (`Util.handlePlayButtonAction`). Left
     * as IDLE it would read as `NowPlayingState.isStalled`, and the mini-player would grey out the
     * one button that resumes it. The genuinely stuck IDLE — the skip-on bound, a paused network
     * failure — always carries the player error that caused it, so it stays IDLE here.
     */
    fun playState(playbackState: Int, playWhenReady: Boolean, loadedWithoutError: Boolean): PlayState =
        if (playbackState == STATE_IDLE && loadedWithoutError) PlayState.PAUSED
        else playState(playbackState, playWhenReady)
}
