// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

/** One way of getting bytes for a track's artwork. */
sealed interface ArtSource {
    /** MediaStore thumbnail via ContentResolver.loadThumbnail (API 29+). */
    data class Thumbnail(val uri: String) : ArtSource

    /** Picture frame embedded in the file, read with eAlvaTag. */
    data class Embedded(val filePath: String) : ArtSource
}

/**
 * Decides — purely — which sources to try and in what order. The I/O lives in
 * [AlbumArtFetcher]; keeping the strategy separate is what makes it testable.
 *
 * Thumbnail first: the system has usually already generated and cached it, so it is
 * both faster and cheaper than parsing the file ourselves.
 */
object ArtSourcePlan {

    fun plan(art: SongArt): List<ArtSource> = buildList {
        if (art.uri.isNotBlank()) add(ArtSource.Thumbnail(art.uri))
        val path = art.filePath
        if (art.hasEmbeddedArt && !path.isNullOrBlank()) add(ArtSource.Embedded(path))
    }
}
