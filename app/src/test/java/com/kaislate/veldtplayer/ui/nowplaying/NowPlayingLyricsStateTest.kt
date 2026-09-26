// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.lyrics.Lyrics
import com.kaislate.veldtplayer.data.lyrics.LyricsProvider
import com.kaislate.veldtplayer.data.lyrics.LyricsResolver
import com.kaislate.veldtplayer.data.lyrics.LyricsSource
import com.kaislate.veldtplayer.data.lyrics.ResolvedLyrics
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM — [LyricsStateHolder] touches no Android type, which is exactly why the lyrics state
 * machine was pulled out of [NowPlayingViewModel] into its own class rather than tested through
 * the view model. `NowPlayingViewModel` takes a real [com.kaislate.veldtplayer.playback
 * .PlaybackConnection], and that class's `init` builds a real `MediaController.Builder` against
 * `PlaybackService` — buildable under Robolectric (`FolderViewModelTest` does), but its connect
 * future never resolves there, so `nowPlaying`/`queue` never leave their seed values and there is
 * no way to drive "the current song" through it at all. [LyricsStateHolder] needs only a plain
 * `Flow<Song?>`, which this file drives directly with a [MutableStateFlow].
 *
 * Every test uses [UnconfinedTestDispatcher] — the same idiom `FolderViewModelTest` uses for
 * `viewModelScope` — so a `MutableStateFlow` write synchronously drives every collector through to
 * its next suspension point, including [LyricsResolver.resolve] itself: that function deliberately
 * runs on the caller's dispatcher rather than forcing `Dispatchers.IO` (see its KDoc), so with fake,
 * non-blocking [LyricsProvider]s here, nothing in this file ever crosses a real thread — a resolve
 * either completes synchronously or suspends on this file's own [delay], which the test scheduler
 * controls.
 *
 * Negative control this file is designed to catch (see the task report): replacing `mapLatest`
 * with a plain `map` in [LyricsStateHolder] reddens `a track change while shown never shows the
 * old track's lyrics for the new one` below, because the superseded track's slow resolve is no
 * longer cancelled and eventually overwrites the new track's already-shown result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingLyricsStateTest {

    private val never: LyricsProvider = LyricsProvider { null }

    private fun song(externalId: String) = Song(
        id = 1L,
        sourceId = "local",
        externalId = externalId,
        uri = "content://media/1",
        filePath = null,
        relativeKey = null,
        title = "t",
        artist = "a",
        album = "al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 0L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    @Test fun `while hidden, the current song is never resolved, even if it changes`() =
        runTest(UnconfinedTestDispatcher()) {
            val calls = mutableListOf<Song>()
            val provider = LyricsProvider { s -> calls += s; Lyrics.Plain("x") }
            val resolver = LyricsResolver("local", provider, never, never, never)
            val songs = MutableStateFlow<Song?>(song("a"))
            val holder = LyricsStateHolder(backgroundScope, songs, resolver, flowOf(false))

            assertEquals(LyricsUi.Hidden, holder.lyrics.value)
            assertEquals(emptyList<Song>(), calls)

            songs.value = song("b")
            assertEquals(
                "a song change while hidden must not resolve",
                emptyList<Song>(),
                calls,
            )
        }

    @Test fun `becoming visible shows Loading before the resolved result`() =
        runTest(UnconfinedTestDispatcher()) {
            val resolver = LyricsResolver("local", LyricsProvider { Lyrics.Plain("hi") }, never, never, never)
            val songs = MutableStateFlow<Song?>(song("a"))
            val holder = LyricsStateHolder(backgroundScope, songs, resolver, flowOf(false))

            val seen = mutableListOf<LyricsUi>()
            backgroundScope.launch { holder.lyrics.collect { seen += it } }

            holder.setVisible(true)

            assertEquals(
                listOf(
                    LyricsUi.Hidden,
                    LyricsUi.Loading,
                    LyricsUi.Shown(ResolvedLyrics(Lyrics.Plain("hi"), LyricsSource.SIDECAR)),
                ),
                seen,
            )
        }

    @Test fun `no lyrics anywhere reports the current onlineEnabled value`() =
        runTest(UnconfinedTestDispatcher()) {
            val resolver = LyricsResolver("local", never, never, never, never)
            val songs = MutableStateFlow<Song?>(song("a"))
            val onlineEnabled = MutableStateFlow(true)
            val holder = LyricsStateHolder(backgroundScope, songs, resolver, onlineEnabled)

            holder.setVisible(true)
            assertEquals(LyricsUi.None(true), holder.lyrics.value)

            // onlineEnabled is read at RESOLUTION time, not itself reactive — flipping it alone
            // must not retroactively rewrite an already-rendered None. Toggling visibility off and
            // on again is what triggers a fresh resolution to pick up the new value.
            onlineEnabled.value = false
            holder.setVisible(false)
            holder.setVisible(true)
            assertEquals(LyricsUi.None(false), holder.lyrics.value)
        }

    @Test fun `a track change while shown never shows the old track's lyrics for the new one`() =
        runTest(UnconfinedTestDispatcher()) {
            val songA = song("a")
            val songB = song("b")
            val provider = LyricsProvider { s ->
                if (s.externalId == "a") {
                    // A slow resolve for the track the user is about to leave. If it is not
                    // cancelled on the track change below, this eventually completes and would
                    // overwrite songB's already-shown result.
                    delay(1_000)
                    Lyrics.Plain("stale-A")
                } else {
                    Lyrics.Plain("fresh-B")
                }
            }
            val resolver = LyricsResolver("local", provider, never, never, never)
            val songs = MutableStateFlow<Song?>(songA)
            val holder = LyricsStateHolder(backgroundScope, songs, resolver, flowOf(false))

            val seen = mutableListOf<LyricsUi>()
            backgroundScope.launch { holder.lyrics.collect { seen += it } }

            holder.setVisible(true)
            songs.value = songB
            advanceUntilIdle()

            assertEquals(
                "the new track's lyrics did not end up shown",
                LyricsUi.Shown(ResolvedLyrics(Lyrics.Plain("fresh-B"), LyricsSource.SIDECAR)),
                holder.lyrics.value,
            )
            assertEquals(
                "the old track's lyrics were shown at some point after the track had already changed",
                false,
                seen.any { it is LyricsUi.Shown && it.resolved.lyrics == Lyrics.Plain("stale-A") },
            )
        }
}
