// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

/**
 * Lyrics for a track, once a provider's raw form (an LRC file, a server response, an
 * unstructured tag) has been reduced to one of two shapes the UI can render without knowing
 * where the lyrics came from.
 */
sealed interface Lyrics {
    /** Time-synced lines, sorted by [LyricLine.timeMs], stable for ties. */
    data class Synced(val lines: List<LyricLine>) : Lyrics

    /** Unsynced lyrics: whatever text the source had, with no timing. */
    data class Plain(val text: String) : Lyrics
}

/** One line of synced lyrics. [text] may be empty — an instrumental gap is a real line, not a gap in the list. */
data class LyricLine(val timeMs: Long, val text: String)

/** Where a track's [Lyrics] came from, so the UI can name the source. */
enum class LyricsSource { SIDECAR, EMBEDDED, SERVER, LRCLIB }

/** [Lyrics] paired with the [LyricsSource] that produced them. */
data class ResolvedLyrics(val lyrics: Lyrics, val source: LyricsSource)
