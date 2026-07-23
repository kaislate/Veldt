package com.kaislate.veldtplayer.data.library.tag

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

    private fun String?.orFallback(other: String?): String? =
        if (this != null && this.isNotBlank()) this else other
}
