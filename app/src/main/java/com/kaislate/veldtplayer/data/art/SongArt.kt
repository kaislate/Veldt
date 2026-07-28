// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The identity Coil loads art for. Deliberately NOT the whole [Song] — the cache key
 * is the song id, and carrying a smaller value keeps list recomposition cheap.
 */
data class SongArt(
    val songId: Long,
    val uri: String,
    val filePath: String?,
    val hasEmbeddedArt: Boolean,
)

fun Song.toSongArt() = SongArt(
    songId = id,
    uri = uri,
    filePath = filePath,
    hasEmbeddedArt = hasEmbeddedArt,
)
