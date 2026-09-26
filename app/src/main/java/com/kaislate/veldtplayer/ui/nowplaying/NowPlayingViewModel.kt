// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.lyrics.LyricsResolver
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.playback.PlaybackConnection
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.PaletteCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The one ViewModel behind both now-playing surfaces — the full screen and the persistent
 * mini-player. It is deliberately activity-scoped at the call site rather than route-scoped:
 * the two surfaces must show the same track, the same palette and the same position, and a
 * per-route instance would run a second palette extraction and a second position collector.
 *
 * Holds NO `MediaController`. Everything goes through the app-scoped [PlaybackConnection]
 * (global constraint 6), whose commands are main-thread only — every method below is called
 * straight from a Compose click handler, so that holds.
 */
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val connection: PlaybackConnection,
    private val paletteCache: PaletteCache,
    resolver: LyricsResolver,
    private val settings: SettingsRepository,
) : ViewModel() {

    val nowPlaying = connection.nowPlaying
    val positionMs = connection.positionMs

    /** The queue behind the current track. Consumed by the P1.4 queue sheet. */
    val queue = connection.queue

    private val _seed = MutableStateFlow(ArtSeed.NEUTRAL)

    /** The TARGET seed for the current track. Theme-INDEPENDENT on purpose: the view model has
     *  no business knowing the theme, and a theme switch must re-derive without re-extracting. */
    val seed: StateFlow<ArtSeed> = _seed.asStateFlow()

    /**
     * The current [Song], derived from [queue] by [connection]'s own `nowPlaying.songId` — the
     * same lookup the class KDoc on [PlaybackConnection] describes. `distinctUntilChanged` is
     * required, not decorative: `nowPlaying` republishes on every player event, including each
     * position-driven timeline update, and without it [lyricsState] would re-resolve on every
     * tick while lyrics are shown.
     */
    private val currentSong: Flow<Song?> =
        combine(connection.nowPlaying, connection.queue) { np, q -> q.firstOrNull { it.id == np.songId } }
            .distinctUntilChanged()

    /** See [LyricsStateHolder]'s KDoc for why the lyrics state machine lives in its own class
     *  rather than directly in this view model. */
    private val lyricsState = LyricsStateHolder(
        scope = viewModelScope,
        songs = currentSong,
        resolver = resolver,
        onlineEnabled = settings.lyricsOnline,
    )
    val lyrics: StateFlow<LyricsUi> = lyricsState.lyrics

    init {
        viewModelScope.launch {
            connection.nowPlaying
                // distinctUntilChanged on the ART, not the state: nowPlaying republishes on
                // every player event (including each position-driven timeline update), and
                // re-extracting per event would defeat the cache's whole purpose.
                .map { it.art }
                .distinctUntilChanged()
                // PaletteCache loads the full-size bitmap itself and dispatches the pixel
                // walk off the main thread; see its KDoc for why it does not accept one.
                .collect { art -> _seed.value = paletteCache.seedFor(art) }
        }
        viewModelScope.launch {
            // `drop(1)`: the memo starts empty, so clearing it again for the FIRST value (the
            // setting's already-resolved current state on collection) would be a no-op that only
            // costs a lock. A real flip — the user toggling it in Settings — is every value after.
            settings.lyricsOnline.drop(1).collect { resolver.clear() }
        }
    }

    fun toggle() = connection.toggle()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(ms: Long) = connection.seekTo(ms)
    fun setShuffle(enabled: Boolean) = connection.setShuffle(enabled)
    fun cycleRepeat() = connection.cycleRepeat()

    /** Jump to a position in [queue]. Consumed by the P1.4 queue sheet. */
    fun skipToQueueIndex(index: Int) = connection.skipToQueueIndex(index)

    private val lyricsViewers = LyricsViewers(lyricsState::setVisible)

    /**
     * Called when a lyrics surface ([viewer] — the in-place pane or the full-screen route) opens
     * or closes (spec §6): resolution happens only while at least one viewer is visible. Keyed by
     * viewer rather than a bare boolean — see [LyricsViewers] for the navigation overlap that a
     * single flag gets wrong.
     */
    fun setLyricsVisible(viewer: Any, visible: Boolean) = lyricsViewers.set(viewer, visible)
}
