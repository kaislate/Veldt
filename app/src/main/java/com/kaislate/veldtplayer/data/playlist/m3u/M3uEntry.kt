// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

/**
 * One playable line of an `.m3u`/`.m3u8` file, together with whatever the preceding `#EXTINF`
 * directive claimed about it.
 *
 * [path] is the file's own text, verbatim: not trimmed, not normalised, not resolved against the
 * playlist's directory. Deciding what it points at is the importer's job, and it needs the
 * original bytes-as-text to do it.
 *
 * [durationSec], [title] and [artist] are hints, not facts. They are whatever the playlist's author
 * wrote, they are absent in most hand-written playlists, and the library's own tags win wherever
 * both exist. All three are null when the entry had no usable `#EXTINF`.
 */
data class M3uEntry(
    val path: String,
    val durationSec: Int?,
    val title: String?,
    val artist: String?,
)
