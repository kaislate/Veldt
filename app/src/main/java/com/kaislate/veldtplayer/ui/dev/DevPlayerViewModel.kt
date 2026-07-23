package com.kaislate.veldtplayer.ui.dev

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kaislate.veldtplayer.playback.PlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DevPlayerViewModel(app: Application) : AndroidViewModel(app) {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var released = false

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(v: Boolean) { _isPlaying.value = v }
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val built = future.get()
            if (released) {
                built.release()
            } else {
                controller = built.also { it.addListener(listener) }
            }
        }, MoreExecutors.directExecutor())
    }

    fun playUri(uri: Uri) {
        controller?.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }

    fun togglePlayPause() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekForward() { controller?.let { it.seekTo(it.currentPosition + 10_000) } }

    override fun onCleared() {
        released = true
        controllerFuture?.cancel(false)
        controllerFuture = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
