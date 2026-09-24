// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import com.kaislate.veldtplayer.playback.TrackRef

/** One way of getting bytes for a track's artwork. */
sealed interface ArtSource {
    /** MediaStore thumbnail via ContentResolver.loadThumbnail (API 29+). */
    data class Thumbnail(val uri: String) : ArtSource

    /** Picture frame embedded in the file, read with eAlvaTag. */
    data class Embedded(val filePath: String) : ArtSource

    /** `getCoverArt` on [ref]'s server (spec §5.6), read with [RemoteArt]. */
    data class Remote(val ref: TrackRef) : ArtSource
}

/**
 * Decides — purely — which sources to try and in what order. The I/O lives in
 * [AlbumArtFetcher]; keeping the strategy separate is what makes it testable.
 *
 * Thumbnail first: the system has usually already generated and cached it, so it is
 * both faster and cheaper than parsing the file ourselves.
 *
 * A remote row ([SongArt.remoteRef] non-null) is an entirely separate branch, not a third rung
 * appended to the local ladder: [SongArt.uri] for such a row is `veldt://track/…`, which
 * `ContentResolver.loadThumbnail` cannot open, and [SongArt.filePath] is null because the file
 * was never synced onto this device (spec §5.6). Trying the local rungs first would just be two
 * guaranteed failures ahead of the one source that can actually answer.
 *
 * A remote row is gated on [SongArt.hasEmbeddedArt] the same way the local [ArtSource.Embedded]
 * rung is: the sync that populated this row already asked the server whether it has art (spec
 * §5.6), so `hasEmbeddedArt == false` here is not "unknown" — it is the server's own answer that
 * a `getCoverArt` call would return the JSON error envelope. Spec §10 (network maps 1:1 to
 * intent) is why the plan is EMPTY rather than trying anyway: a request whose answer is already
 * known is chatter, not art discovery.
 */
object ArtSourcePlan {

    fun plan(art: SongArt): List<ArtSource> {
        val remote = art.remoteRef
        if (remote != null) {
            return if (art.hasEmbeddedArt) listOf(ArtSource.Remote(remote)) else emptyList()
        }
        return buildList {
            if (art.uri.isNotBlank()) add(ArtSource.Thumbnail(art.uri))
            val path = art.filePath
            if (art.hasEmbeddedArt && !path.isNullOrBlank()) add(ArtSource.Embedded(path))
        }
    }
}
