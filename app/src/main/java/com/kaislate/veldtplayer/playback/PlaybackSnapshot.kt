// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

enum class PlayState { PLAYING, PAUSED, BUFFERING, ENDED, IDLE }

data class PlaybackSnapshot(
    val state: PlayState,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val title: String?,
    val artist: String?,
    val album: String?,
)
