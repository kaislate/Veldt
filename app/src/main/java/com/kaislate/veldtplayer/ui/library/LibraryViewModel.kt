package com.kaislate.veldtplayer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Library UI state + play-on-tap. Owns no `MediaController` of its own (global
 * constraint 6): it injects the app-scoped [PlaybackConnection], so a tap issued before
 * the session has connected is queued and replayed instead of silently dropped.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    val songs: StateFlow<List<Song>> =
        repo.songs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun scan() = repo.requestScan()

    /** Play-in-context: the tapped song queues the whole list it was tapped in (spec §5). */
    fun onSongTap(song: Song) {
        val list = songs.value
        playback.playFrom(list, list.indexOfFirst { it.id == song.id })
    }
}
