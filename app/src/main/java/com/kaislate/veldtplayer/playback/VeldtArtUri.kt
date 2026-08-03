// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import com.kaislate.veldtplayer.data.art.SongArt

/**
 * The `artworkUri` Veldt puts on every session [androidx.media3.common.MediaItem], and the
 * only uri [VeldtBitmapLoader] will load.
 *
 * **Why a private scheme instead of the track's own `content://` uri.** Media3's default
 * artwork loader (`DataSourceBitmapLoader`) opens whatever `artworkUri` says and hands the
 * bytes to `BitmapFactory`. Pointed at the audio uri it downloads the MP3 and tries to
 * decode it as an image — silently, producing no art. The legacy
 * `content://media/external/audio/albumart/<albumId>` uri is worse: deprecated and
 * unreliable from API 29, which is this app's floor. A scheme nothing else can open fails
 * *loudly* in the one case that matters — if these items ever reach a loader that is not
 * ours, that loader reports an error instead of quietly chewing through the music file.
 *
 * The uri carries the whole [SongArt] rather than just the song id so resolving it needs no
 * database round trip on the notification path — and so [VeldtBitmapLoader] stays a pure
 * function of its argument.
 *
 * **The id is what keeps two tracks apart.** Media3 wraps the session's loader in
 * `CacheBitmapLoader`, which caches by uri equality, so two different songs whose artwork
 * uris compared equal would share one cover. Every field is folded in, and the id alone
 * already separates any two library rows.
 */
object VeldtArtUri {

    /** Deliberately not `content` or `file`: no other component can open this. */
    const val SCHEME = "veldt-art"

    private const val AUTHORITY = "song"
    private const val Q_SOURCE = "src"
    private const val Q_PATH = "path"
    private const val Q_EMBEDDED = "emb"

    /**
     * `appendQueryParameter` percent-encodes, and `getQueryParameter` decodes, so a file path
     * containing `&`, `=` or `?` round-trips instead of being re-read as further parameters.
     */
    fun of(art: SongArt): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(art.songId.toString())
        .appendQueryParameter(Q_SOURCE, art.uri)
        // Appended only when non-null, so "no path" and "empty path" stay distinguishable:
        // absent -> getQueryParameter returns null, present-and-empty -> returns "".
        .apply { art.filePath?.let { appendQueryParameter(Q_PATH, it) } }
        .appendQueryParameter(Q_EMBEDDED, if (art.hasEmbeddedArt) "1" else "0")
        .build()

    /**
     * The inverse of [of], or null for anything this loader must not touch — including the
     * track's own audio uri, which is exactly the input the private scheme exists to reject.
     */
    fun parse(uri: Uri): SongArt? {
        if (!SCHEME.equals(uri.scheme, ignoreCase = true)) return null
        if (uri.authority != AUTHORITY) return null
        val id = uri.pathSegments.singleOrNull()?.toLongOrNull() ?: return null
        return SongArt(
            songId = id,
            // A blank source is legal — ArtSourcePlan simply drops the thumbnail attempt and
            // the embedded frame still gets its turn.
            uri = uri.getQueryParameter(Q_SOURCE).orEmpty(),
            filePath = uri.getQueryParameter(Q_PATH),
            hasEmbeddedArt = uri.getQueryParameter(Q_EMBEDDED) == "1",
        )
    }
}
