package com.kaislate.veldtplayer.ui.dev

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
        // Dev proof: files often have no embedded title tag, so seed the MediaItem
        // title with the picked file's display name — the seam then shows *something*
        // in the bus readout. Real title/tag resolution is a P1.2 library concern.
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(displayName(uri)).build())
            .build()
        controller?.apply {
            setMediaItem(item)
            prepare()
            play()
        }
    }

    private fun displayName(uri: Uri): String =
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Unknown"

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
