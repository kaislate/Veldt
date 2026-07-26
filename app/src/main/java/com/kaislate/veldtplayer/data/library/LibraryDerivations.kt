package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song

/**
 * Pure projections used by any LibrarySource that stores only songs (denormalized).
 *
 * Grouping is by [LibraryKeys.normalize] so case and whitespace variants collapse to
 * one row; the displayed [Album.name] / [Artist.name] is the FIRST spelling seen in
 * the input. Callers pass title-sorted rows (SongDao.observeAllSongs), so "first
 * seen" is deterministic.
 */
object LibraryDerivations {

    fun deriveAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { LibraryKeys.normalize(it.album) }
            .map { (key, rows) ->
                Album(
                    key = key,
                    name = rows.first().album.trim(),
                    albumArtist = rows.firstNotNullOfOrNull { it.albumArtist },
                    songCount = rows.size,
                )
            }
            .sortedBy { it.key }

    fun deriveArtists(songs: List<Song>): List<Artist> =
        songs.groupBy { LibraryKeys.normalize(it.artist) }
            .map { (key, rows) ->
                Artist(
                    key = key,
                    name = rows.first().artist.trim(),
                    albumCount = rows.map { LibraryKeys.normalize(it.album) }.distinct().size,
                    songCount = rows.size,
                )
            }
            .sortedBy { it.key }
}
