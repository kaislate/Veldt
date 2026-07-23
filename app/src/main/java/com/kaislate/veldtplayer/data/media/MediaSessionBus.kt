package com.kaislate.veldtplayer.data.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MediaSessionBus {
    private var controller: MediaController? = null

    private val _activePackage = MutableStateFlow<String?>(null)
    val activePackage: StateFlow<String?> = _activePackage

    // Numeric state (Compat)
    private val _playbackState = MutableStateFlow<Int?>(null)
    val playbackState: StateFlow<Int?> = _playbackState

    // Full state (for progress)
    private val _playback = MutableStateFlow<PlaybackState?>(null)
    val playback: StateFlow<PlaybackState?> = _playback

    private val _metadata = MutableStateFlow<MediaMetadata?>(null)
    val metadata: StateFlow<MediaMetadata?> = _metadata

    private val _albumArt = MutableStateFlow<Bitmap?>(null)
    val albumArt: StateFlow<Bitmap?> = _albumArt

    private val _customActions = MutableStateFlow<List<PlaybackState.CustomAction>>(emptyList())
    val customActions: StateFlow<List<PlaybackState.CustomAction>> = _customActions

    // The playing app's status-bar (notification small) icon. Like album art, it's
    // resolved by the listener (which has a Context to load the Icon) — see
    // MediaNotificationListener.pushSmallIcon.
    private val _smallIcon = MutableStateFlow<Bitmap?>(null)
    val smallIcon: StateFlow<Bitmap?> = _smallIcon

    fun attachController(c: MediaController?) {
        controller = c
        _activePackage.value = c?.packageName
        _playbackState.value = c?.playbackState?.state
        _playback.value = c?.playbackState // full object: position/speed for progress
        _metadata.value = c?.metadata
        // Album art is resolved by the listener (which has a Context to load
        // URI-based art), not here — see MediaNotificationListener.pushArt.
        _customActions.value = c?.playbackState?.customActions ?: emptyList()
        // Clear the previous app's status-bar icon; the listener re-pushes the new
        // one right after this call (in reselect).
        _smallIcon.value = null
    }

    /** Set the source app's status-bar small icon (from its media notification). */
    fun setSmallIcon(bmp: Bitmap?) { _smallIcon.value = bmp }

    /**
     * Emit album art only when the PIXELS actually change. Some apps (VLC) parcel
     * a brand-new Bitmap instance of the same art on every play/pause — emitting
     * each instance made the image reload and blink. Called by the listener after
     * it resolves art from the session (bitmap keys or a loaded URI).
     */
    fun setAlbumArt(newArt: Bitmap?, allowNull: Boolean = false) {
        val cur = _albumArt.value
        when {
            newArt == null -> if (allowNull) _albumArt.value = null
            cur == null -> _albumArt.value = newArt
            cur.width == newArt.width && cur.height == newArt.height &&
                runCatching { cur.sameAs(newArt) }.getOrDefault(false) -> Unit // identical pixels: keep the old instance
            else -> _albumArt.value = newArt
        }
    }

    fun updatePlaybackState(state: Int?) {
        _playbackState.value = state
        // tries to keep the PlaybackState object updated
        val current = _playback.value
        if (current != null && current.state != state) {
            _playback.value = PlaybackState.Builder(current).setState(
                state ?: PlaybackState.STATE_PAUSED,
                current.position,
                current.playbackSpeed
            ).build()
        }
    }

    fun updatePlayback(playback: PlaybackState?) {
        _playback.value = playback
        _playbackState.value = playback?.state
        _customActions.value = playback?.customActions ?: emptyList()
    }

    fun updateMetadata(meta: MediaMetadata?) {
        // Some apps (VLC) momentarily re-post null metadata around pause/seek —
        // swapping to the placeholder and back made the whole panel blink. Keep
        // the last known metadata; attachController() resets it on session change.
        if (meta == null) return
        _metadata.value = meta
        // Album art resolved by the listener (see pushArt).
    }

    // ---- Real controls ----
    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun togglePlayPause() {
        when (_playbackState.value) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> pause()
            else -> play()
        }
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()
    fun seekTo(posMs: Long) = controller?.transportControls?.seekTo(posMs)

    fun sendCustomAction(a: PlaybackState.CustomAction) {
        controller?.transportControls?.sendCustomAction(a, null)
    }
}