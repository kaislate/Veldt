// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import coil.key.Keyer
import coil.request.Options

/**
 * Cache key is the song id alone, so the list thumbnail, the mini-player thumb and
 * the full-screen art all share ONE decoded bitmap. That shared instance is what
 * makes the shared-element morph (Task 8) look continuous rather than like a
 * cross-fade between two different decodes.
 *
 * The ONE exception is a request that asked to be decoded small (see [ArtDecode]). Such a
 * bitmap must not be shared, because a surface that wants full resolution would otherwise
 * be served a fraction of one — the morph would land on a blurry cover. It therefore gets
 * its own key, and the shared key is left byte-identical for everyone else.
 */
class AlbumArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String {
        // The Room surrogate `songs.id`, NOT `sourceId:externalId` — this is a process-lifetime
        // memory/disk cache key, and the surrogate is unique among live rows and never reissued
        // (see [SongArt]). Unlike the Media3 mediaId it does not have to survive a database wipe:
        // a wipe takes the library with it, and the cache being cold afterwards is correct.
        val shared = "song-art-${data.songId}"
        val sample = options.artDecodeSample()
        return if (sample == ArtDecode.FULL) shared else "$shared@1-$sample"
    }
}
