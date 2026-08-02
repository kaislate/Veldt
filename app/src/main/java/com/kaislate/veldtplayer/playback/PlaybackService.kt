// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.kaislate.veldtplayer.MainActivity
import com.kaislate.veldtplayer.data.media.MediaSessionBus

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
    private var bitmapLoader: VeldtBitmapLoader? = null

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

        // Without this Media3 uses DataSourceBitmapLoader, which would open whatever
        // `artworkUri` says and hand the bytes to BitmapFactory — so the ONLY artwork uri
        // that could ever work is one no default loader understands. See [VeldtArtUri].
        //
        // CacheBitmapLoader is what lets the session and the bus adapter share one decode:
        // both ask for the current track's cover, and it holds the last request. (The
        // session would wrap the loader in one anyway; wrapping here puts the adapter
        // inside the same cache instead of outside it.)
        val loader = VeldtBitmapLoader(this)
        bitmapLoader = loader
        val sessionLoader = CacheBitmapLoader(loader)

        session = MediaLibrarySession.Builder(this, exo, LibraryCallback())
            .setSessionActivity(appLaunchIntent())
            .setBitmapLoader(sessionLoader)
            .build()
        busAdapter = PlayerBusAdapter(exo, packageName, sessionLoader).also { it.attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onDestroy() {
        busAdapter?.detach()
        // Detach first, then clear: the adapter can push during teardown, and a push landing
        // after the reset would refill the bus with the state this is meant to drop.
        //
        // MediaSessionBus is a process-scoped singleton, so without this it keeps serving the
        // dead service's last track — and holds its decoded full-size cover bitmap — for as
        // long as the process lives.
        MediaSessionBus.reset()
        session?.release()
        player?.release()
        // Cancels any art load still walking the ladder. Safe in any order relative to the
        // reset above only because `detach()` already invalidated the adapter's in-flight
        // request: release completes those futures exceptionally, which still runs their
        // listeners.
        bitmapLoader?.release()
        session = null
        player = null
        busAdapter = null
        bitmapLoader = null
        super.onDestroy()
    }

    /**
     * What tapping the media notification (or Samsung's media panel, or the lock
     * screen controls) opens. Without a session activity the platform has nothing
     * to launch and the tap is silently inert — `dumpsys media_session` reports
     * `launchIntent=null`.
     *
     * `FLAG_UPDATE_CURRENT` so a re-created service replaces rather than
     * duplicates the intent; `FLAG_IMMUTABLE` is required from API 31 and is
     * correct here regardless, since nothing needs to fill in extras.
     */
    private fun appLaunchIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // Resume the existing task rather than stacking a second copy of the
            // activity on top of the one the user already has.
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Minimal callback; browse tree arrives in P1.2. Default player-command
     *  handling (play/pause/seek/next/prev) is inherited. */
    private inner class LibraryCallback : MediaLibrarySession.Callback
}
