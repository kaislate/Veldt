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
