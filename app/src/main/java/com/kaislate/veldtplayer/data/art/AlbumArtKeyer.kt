package com.kaislate.veldtplayer.data.art

import coil.key.Keyer
import coil.request.Options

/**
 * Cache key is the song id alone, so the list thumbnail, the mini-player thumb and
 * the full-screen art all share ONE decoded bitmap. That shared instance is what
 * makes the shared-element morph (Task 8) look continuous rather than like a
 * cross-fade between two different decodes.
 */
class AlbumArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String = "song-art-${data.songId}"
}
