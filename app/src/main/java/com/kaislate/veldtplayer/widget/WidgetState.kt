// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.media.session.PlaybackState
import androidx.compose.ui.graphics.toArgb
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.theme.ArtSeed

/**
 * What the widget shows, reduced from [com.kaislate.veldtplayer.data.media.MediaSessionBus]'s
 * framework types to the few facts a `RemoteViews` needs. A pure value so that "which state for
 * which bus values" is testable without a launcher, and so the observer can drop a re-published
 * but identical state instead of pushing the same `RemoteViews` over IPC again.
 */
sealed interface WidgetState {

    /** Nothing played yet (or the playback service has gone and reset the bus): "Tap to open Veldt". */
    data object Empty : WidgetState

    data class Track(val title: String, val artist: String, val isPlaying: Boolean) : WidgetState

    companion object {
        /**
         * [title]/[artist] as the bus's `MediaMetadata` holds them, [playbackState] as its
         * `PlaybackState.STATE_*`. `PlayerBusAdapter` publishes `""` for a missing title or
         * artist before a queue is loaded, so blank-and-blank is the empty state, not a track
         * with no name.
         *
         * BUFFERING counts as playing: the adapter reports it whatever `playWhenReady` is, and
         * the button that matters mid-stream is pause — a play glyph there would invite a tap
         * that starts nothing new.
         */
        fun of(title: String?, artist: String?, playbackState: Int?): WidgetState {
            if (title.isNullOrBlank() && artist.isNullOrBlank()) return Empty
            val playing = playbackState == PlaybackState.STATE_PLAYING ||
                playbackState == PlaybackState.STATE_BUFFERING
            return Track(title.orEmpty(), artist.orEmpty(), playing)
        }
    }
}

/**
 * The widget's colours, as ARGB ints for `RemoteViews`, for ONE theme.
 *
 * Straight from [ArtSeed.colors], the same solve now playing and the pill use, because the
 * widget's ground IS `bg` — a solid fill, with no artwork composited under it — so the
 * ratios `colors` constructs against `bg` are exactly the ratios the widget renders: 7:1 for
 * the title, 4.5:1 for the artist (solved, never alpha-dimmed), 3:1 for the accent. The skip
 * buttons take [title]'s tone and play/pause the accent, so the primary control keeps the
 * cover's chroma the way now playing's does.
 */
data class WidgetTones(val ground: Int, val title: Int, val subtitle: Int, val skip: Int, val playPause: Int) {
    companion object {
        fun of(seed: ArtSeed, isLight: Boolean): WidgetTones {
            val c = seed.colors(isLight)
            return WidgetTones(
                ground = c.bg.toArgb(),
                title = c.onBg.toArgb(),
                subtitle = c.onBgSecondary.toArgb(),
                skip = c.onBg.toArgb(),
                playPause = c.accent.toArgb(),
            )
        }
    }
}

/**
 * The tones for the launcher's not-night and night slots. API 31+ hands the launcher both and
 * lets it pick by the system's night mode; 29–30 get the one [WidgetRenderer] picks.
 */
data class WidgetTonePair(val light: WidgetTones, val dark: WidgetTones) {

    /**
     * The user's Light/Dark/System choice applied, as the pill and every screen apply it: an
     * explicit choice fills BOTH slots with its theme, so the system's night mode cannot
     * override it; [ThemeMode.SYSTEM] keeps one of each.
     */
    fun resolvedFor(mode: ThemeMode): WidgetTonePair = when (mode) {
        ThemeMode.LIGHT -> WidgetTonePair(light, light)
        ThemeMode.DARK -> WidgetTonePair(dark, dark)
        ThemeMode.SYSTEM -> this
    }

    companion object {
        fun of(seed: ArtSeed) = WidgetTonePair(WidgetTones.of(seed, isLight = true), WidgetTones.of(seed, isLight = false))
    }
}
