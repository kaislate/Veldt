// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The one place a [Song] becomes a session [MediaItem].
 *
 * A free function rather than a method on [PlaybackConnection] so it can be asserted
 * directly: the connection cannot be constructed without a live `MediaController`, and the
 * artwork uri below is exactly the kind of thing that is silently correct until someone
 * looks at a lock screen.
 *
 * [playableUri] is passed in rather than read from the repository here, so the
 * `LibrarySource` resolution seam for non-local sources stays with the caller that owns it.
 *
 * ## The mediaId is `sourceId:externalId`, and deliberately not [Song.id]
 *
 * The surrogate is unique, but it is **reassigned by a database wipe** — and a mediaId has to
 * outlive one. It is the handle a restored session hands back to the app (see
 * [PlaybackConnection.publish]'s TODO), so after a destructive migration a surrogate-keyed
 * mediaId would either name a different track or name nothing. `(sourceId, externalId)` is the
 * identity that survives, because it is what the source itself calls the track.
 *
 * The encoding is injective for one reason and it does not live here: [SourceRegistry] rejects
 * a source id containing `':'` at construction, so the **first** colon is unambiguously the
 * boundary and the split is exact. That guarantee is a tested `require`, not a convention this
 * file has to remember (Global Constraint 10).
 */
internal fun sessionMediaItem(song: Song, playableUri: String): MediaItem = MediaItem.Builder()
    .setMediaId("${song.sourceId}:${song.externalId}")
    .setUri(Uri.parse(playableUri))
    // Through DisplayNames, like every other surface. This metadata is what the
    // notification, the lock screen and Android Auto render, so a raw tag here means
    // the one place the user cannot correct is also the one place still showing
    // "<unknown>" — or, once that is cleaned to blank, showing nothing at all.
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(song.displayTitle())
            .setArtist(song.displayArtist())
            .setAlbumTitle(song.displayAlbum())
            // NOT the playable uri, and not the deprecated albumart:// path — see
            // [VeldtArtUri]. Built from the same [SongArt] the browse list and the
            // now-playing screen use, so the notification cannot disagree with the app
            // about what this track's cover is.
            .setArtworkUri(VeldtArtUri.of(song.toSongArt()))
            .build()
    )
    .build()
