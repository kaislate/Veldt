// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song

enum class RepeatMode { OFF, ALL, ONE }

/**
 * Media3 exposes repeat as ints (Player.REPEAT_MODE_OFF/ONE/ALL = 0/1/2). They are
 * inlined here rather than imported so this stays pure JVM and unit-testable — the
 * same discipline P1.1 used for [PlaybackMapper].
 */
object RepeatModes {
    private const val PLAYER_OFF = 0
    private const val PLAYER_ONE = 1
    private const val PLAYER_ALL = 2

    fun fromPlayer(mode: Int): RepeatMode = when (mode) {
        PLAYER_ONE -> RepeatMode.ONE
        PLAYER_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    fun toPlayer(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> PLAYER_OFF
        RepeatMode.ONE -> PLAYER_ONE
        RepeatMode.ALL -> PLAYER_ALL
    }

    /** The order the UI toggle cycles through: off -> all -> one -> off. */
    fun next(mode: RepeatMode): RepeatMode = when (mode) {
        RepeatMode.OFF -> RepeatMode.ALL
        RepeatMode.ALL -> RepeatMode.ONE
        RepeatMode.ONE -> RepeatMode.OFF
    }
}

/** Everything the mini-player and now-playing surface need, and nothing more. */
data class NowPlayingState(
    val songId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val art: SongArt?,
    val playState: PlayState,
    val durationMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
) {
    val isActive: Boolean get() = songId != null
    val isPlaying: Boolean get() = playState == PlayState.PLAYING

    /**
     * A track is on screen but the player is not prepared to play it, so the transport
     * would do nothing.
     *
     * This is reachable, and not only by exotic means. When
     * [com.kaislate.veldtplayer.playback.PlaybackConnection]'s skip-on bound engages — a
     * queue where every file is undecodable, e.g. an unmounted SD card against stale
     * MediaStore rows — the player is left in `STATE_IDLE`, and `toggle`/`next`/`previous`
     * never call `prepare()`. Only a fresh `playFrom` recovers. Exposed so the UI can render
     * those controls DISABLED rather than offering three buttons that silently do nothing.
     */
    val isStalled: Boolean get() = isActive && playState == PlayState.IDLE

    /** Placeholder glyph when there's no artwork (spec §4). */
    val initial: Char get() = title.firstOrNull { it.isLetterOrDigit() } ?: '♪'

    companion object {
        val EMPTY = NowPlayingState(
            songId = null, title = "", artist = "", album = "", art = null,
            playState = PlayState.IDLE, durationMs = 0L, shuffle = false,
            repeat = RepeatMode.OFF, hasNext = false, hasPrevious = false,
        )

        /**
         * [playerDurationMs] is the live player's duration, which is authoritative once
         * known but is C.TIME_UNSET (a large negative) before preparation completes —
         * so any non-positive value falls back to the library's stored duration and the
         * scrub bar never renders against a nonsense length.
         */
        fun from(
            song: Song?,
            playState: PlayState,
            playerDurationMs: Long,
            shuffle: Boolean,
            repeat: RepeatMode,
            hasNext: Boolean,
            hasPrevious: Boolean,
        ): NowPlayingState {
            if (song == null) {
                return EMPTY.copy(
                    playState = playState, shuffle = shuffle, repeat = repeat,
                    hasNext = hasNext, hasPrevious = hasPrevious,
                )
            }
            return NowPlayingState(
                songId = song.id,
                // DisplayNames, not ifBlank: a missing tag can also arrive as MediaStore's
                // literal "<unknown>", which is not blank. One definition for the whole app.
                title = DisplayNames.title(song.title),
                artist = DisplayNames.artist(song.artist),
                album = DisplayNames.album(song.album),
                art = song.toSongArt(),
                playState = playState,
                durationMs = if (playerDurationMs > 0L) playerDurationMs else song.durationMs,
                shuffle = shuffle,
                repeat = repeat,
                hasNext = hasNext,
                hasPrevious = hasPrevious,
            )
        }
    }
}
