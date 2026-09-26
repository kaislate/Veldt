// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.lyrics.LyricsResolver
import com.kaislate.veldtplayer.data.lyrics.ResolvedLyrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/** What the now-playing lyrics pane/screen renders — spec §6, §7. */
sealed interface LyricsUi {
    /** Lyrics are not open for the current track; nothing has been (or will be) resolved. */
    data object Hidden : LyricsUi

    /** Lyrics are open and a resolution is in flight. */
    data object Loading : LyricsUi

    /** A provider answered. */
    data class Shown(val resolved: ResolvedLyrics) : LyricsUi

    /** Every provider answered null. [onlineEnabled] is read at resolution time so the "Turn on
     *  online lyrics" link (spec §7) only ever appears when that is actually still true. */
    data class None(val onlineEnabled: Boolean) : LyricsUi
}

/**
 * The lyrics state machine `NowPlayingViewModel` owns — pulled into its own class because
 * [com.kaislate.veldtplayer.playback.PlaybackConnection] holds the one real `MediaController` and
 * cannot be driven from a JVM test (see the task report), while this class needs nothing but a
 * plain [Flow] of the current [Song] and is fully testable on its own.
 *
 * **Resolution happens only while [setVisible] has been called with `true`, and only for the
 * current [songs] value at that moment** (spec §6): [combine] pairs visibility with the current
 * song, and [mapLatest] is what makes a track change safe while shown — the previous pair's
 * still-running resolve is CANCELLED the instant a new (visibility, song) pair arrives, so a slow
 * answer for the track the user has already left can never land after the new track's own answer
 * (Review Focus 3). `Loading` is assigned synchronously as the very first thing each pair's block
 * does, strictly before the suspending [LyricsResolver.resolve] call, so it is always observable
 * before that call's eventual result — even when the result arrives fast enough that a naive
 * "did Loading ever appear" check racing the two might otherwise miss it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsStateHolder(
    scope: CoroutineScope,
    songs: Flow<Song?>,
    private val resolver: LyricsResolver,
    private val onlineEnabled: Flow<Boolean>,
) {
    private val visible = MutableStateFlow(false)

    private val _lyrics = MutableStateFlow<LyricsUi>(LyricsUi.Hidden)
    val lyrics: StateFlow<LyricsUi> = _lyrics.asStateFlow()

    init {
        scope.launch {
            combine(visible, songs) { isVisible, song -> isVisible to song }
                .distinctUntilChanged()
                .mapLatest { (isVisible, song) ->
                    if (!isVisible || song == null) {
                        LyricsUi.Hidden
                    } else {
                        _lyrics.value = LyricsUi.Loading
                        val resolved = resolver.resolve(song)
                        resolved?.let { LyricsUi.Shown(it) } ?: LyricsUi.None(onlineEnabled.first())
                    }
                }
                .collect { _lyrics.value = it }
        }
    }

    fun setVisible(value: Boolean) {
        visible.value = value
    }
}
