package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song

/** Pure projections used by any LibrarySource that stores only songs (denormalized). */
object LibraryDerivations {

    fun deriveAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { it.album }
            .map { (name, rows) ->
                Album(
                    name = name,
                    albumArtist = rows.firstNotNullOfOrNull { it.albumArtist },
                    songCount = rows.size,
                )
            }
            .sortedBy { it.name.lowercase() }

    fun deriveArtists(songs: List<Song>): List<Artist> =
        songs.groupBy { it.artist }
            .map { (name, rows) ->
                Artist(
                    name = name,
                    albumCount = rows.map { it.album }.distinct().size,
                    songCount = rows.size,
                )
            }
            .sortedBy { it.name.lowercase() }
}
