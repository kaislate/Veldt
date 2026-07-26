package com.kaislate.veldtplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One ViewModel for every browse surface. It holds NO MediaController — playback goes
 * through the injected [PlaybackConnection] (global constraint 6).
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {

    val songs: StateFlow<List<Song>> = repo.songs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = repo.albums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = repo.artists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Playback failures (unreadable/deleted files) surfaced for the snackbar. */
    val errors = connection.errors

    fun songsForAlbum(key: String): Flow<List<Song>> = repo.songsForAlbum(key)

    fun songsForArtist(key: String): Flow<List<Song>> = repo.songsForArtist(key)

    fun scan() {
        repo.requestScan()
    }

    /**
     * Play-in-context: the tapped list becomes the queue.
     *
     * Main-thread only, because [PlaybackConnection]'s commands are `@MainThread`
     * (its pending-command deque is unsynchronized and `MediaController` verifies its
     * application thread). Every caller is a Compose click handler, so this holds.
     */
    fun play(songs: List<Song>, index: Int) = connection.playFrom(songs, index)
}
