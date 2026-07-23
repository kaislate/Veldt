package com.kaislate.veldtplayer.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession

/**
 * Media3 MediaLibraryService: hosts the ExoPlayer and publishes a
 * MediaLibrarySession. Media3 auto-manages the media notification and the
 * mediaPlayback foreground service. The browsable library tree is empty in
 * P1.1 (default callback) — it is filled in P1.2.
 */
class PlaybackService : MediaLibraryService() {

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var busAdapter: PlayerBusAdapter? = null

    override fun onCreate() {
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo

        session = MediaLibrarySession.Builder(this, exo, LibraryCallback()).build()
        busAdapter = PlayerBusAdapter(exo, packageName).also { it.attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onDestroy() {
        busAdapter?.detach()
        session?.release()
        player?.release()
        session = null
        player = null
        busAdapter = null
        super.onDestroy()
    }

    /** Minimal callback; browse tree arrives in P1.2. Default player-command
     *  handling (play/pause/seek/next/prev) is inherited. */
    private inner class LibraryCallback : MediaLibrarySession.Callback
}
