// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.tag

import com.kaislate.veldtplayer.data.library.DisplayNames

/**
 * Pure, framework-free selection: an eAlvaTag-parsed value wins when present and
 * non-blank, otherwise the MediaStore-derived [fallback] value is kept. Numeric
 * fields prefer the parsed value when non-null. `hasEmbeddedArt` is the OR of both
 * sources (MediaStore's is normally `false`). Unit-tested on the JVM.
 */
object TagMerge {
    fun merge(parsed: TrackTags?, fallback: TrackTags): TrackTags {
        if (parsed == null) return fallback
        return TrackTags(
            title = parsed.title.orFallback(fallback.title),
            artist = parsed.artist.orFallback(fallback.artist),
            album = parsed.album.orFallback(fallback.album),
            albumArtist = parsed.albumArtist.orFallback(fallback.albumArtist),
            trackNumber = parsed.trackNumber ?: fallback.trackNumber,
            discNumber = parsed.discNumber ?: fallback.discNumber,
            year = parsed.year ?: fallback.year,
            hasEmbeddedArt = parsed.hasEmbeddedArt || fallback.hasEmbeddedArt,
        )
    }

    /**
     * A field is kept only if it SAYS something, on both sides.
     *
     * [DisplayNames.isMissing], not `isNotBlank()`: the `<unknown>` sentinel is written
     * into the files' own ID3 frames by some downloaders, not just into MediaStore's
     * columns — verified on device, where 29 of 31 scanned tracks carried it in the tag
     * itself. Cleaning it at the MediaStore boundary alone let the parsed value walk it
     * straight back in, because a non-blank `<unknown>` beat a correctly-blank fallback.
     */
    private fun String?.orFallback(other: String?): String? =
        DisplayNames.tagOrNull(this) ?: DisplayNames.tagOrNull(other)
}
