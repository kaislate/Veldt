// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.playback.PlaybackConnection
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.PaletteCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
) : ViewModel() {

    val nowPlaying = connection.nowPlaying
    val positionMs = connection.positionMs

    /** The queue behind the current track. Consumed by the P1.4 queue sheet. */
    val queue = connection.queue

    private val _seed = MutableStateFlow(ArtSeed.NEUTRAL)

    /** The TARGET seed for the current track. Theme-INDEPENDENT on purpose: the view model has
     *  no business knowing the theme, and a theme switch must re-derive without re-extracting. */
    val seed: StateFlow<ArtSeed> = _seed.asStateFlow()

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
    }

    fun toggle() = connection.toggle()
    fun next() = connection.next()
    fun previous() = connection.previous()
    fun seekTo(ms: Long) = connection.seekTo(ms)
    fun setShuffle(enabled: Boolean) = connection.setShuffle(enabled)
    fun cycleRepeat() = connection.cycleRepeat()

    /** Jump to a position in [queue]. Consumed by the P1.4 queue sheet. */
    fun skipToQueueIndex(index: Int) = connection.skipToQueueIndex(index)
}
