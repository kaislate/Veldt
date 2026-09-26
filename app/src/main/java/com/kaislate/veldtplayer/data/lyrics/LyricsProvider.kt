// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * One source of lyrics for a [Song]. Returns null both on genuine absence and on any failure —
 * a provider never throws; every IO, parse or network error degrades to "no lyrics from this
 * source" so a later resolver's fallback chain can try the next provider instead of crashing
 * the caller. Implementations must never put a URL, a file path or a credential into a log
 * line or an exception message.
 */
fun interface LyricsProvider {
    suspend fun lyricsFor(song: Song): Lyrics?
}
