package com.kaislate.veldtplayer.ui.library

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Library UI state + play-on-tap. Reuses the P1.1 `MediaController` connection pattern
 * verbatim from `DevPlayerViewModel` (SessionToken → MediaController.Builder.buildAsync,
 * released in onCleared with the async-race guard) so playback goes through the same
 * `PlaybackService` MediaLibraryService.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    app: Application,
    private val repo: MusicRepository,
) : AndroidViewModel(app) {

    val songs: StateFlow<List<Song>> =
        repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var released = false

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val built = future.get()
            if (released) built.release() else controller = built
        }, MoreExecutors.directExecutor())
    }

    fun scan() = repo.requestScan()

    fun onSongTap(song: Song) {
        val item = MediaItem.Builder()
            .setUri(Uri.parse(repo.playableUri(song)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .build()
            )
            .build()
        controller?.apply {
            setMediaItem(item)
            prepare()
            play()
        }
    }

    override fun onCleared() {
        released = true
        controllerFuture?.cancel(false)
        controllerFuture = null
        controller?.release()
        controller = null
        super.onCleared()
    }
}
