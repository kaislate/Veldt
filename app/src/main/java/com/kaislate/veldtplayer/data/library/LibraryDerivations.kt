package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song

/**
 * Pure projections used by any LibrarySource that stores only songs (denormalized).
 *
 * Grouping is by [LibraryKeys] so case and whitespace variants collapse to one row; the
 * displayed [Album.name] / [Artist.name] is the FIRST spelling seen in the input. Callers
 * pass title-sorted rows (SongDao.observeAllSongs), so "first seen" is deterministic.
 */
object LibraryDerivations {

    fun deriveAlbums(songs: List<Song>): List<Album> =
        songs.groupBy { LibraryKeys.albumKey(it) }
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
        songs.groupBy { LibraryKeys.artistKey(it) }
            .map { (key, rows) ->
                Artist(
                    key = key,
                    name = rows.first().artist.trim(),
                    albumCount = rows.map { LibraryKeys.albumKey(it) }.distinct().size,
                    songCount = rows.size,
                )
            }
            .sortedBy { it.key }

    /**
     * Album tracks in disc-then-track order, falling back to title for untagged files.
     * Untagged tracks sort last rather than first — a stray untagged file belongs at the
     * end of the record, not ahead of track 1.
     */
    fun sortAlbumTracks(songs: List<Song>): List<Song> =
        songs.sortedWith(
            compareBy(
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            )
        )

    /**
     * An artist's songs grouped by album, then in [sortAlbumTracks] order within each.
     * Grouped on the compound album key rather than the title, so two same-titled albums
     * stay contiguous blocks instead of interleaving.
     */
    fun sortArtistTracks(songs: List<Song>): List<Song> =
        songs.sortedWith(
            compareBy(
                { LibraryKeys.albumKey(it) },
                { it.discNumber ?: 1 },
                { it.trackNumber ?: Int.MAX_VALUE },
                { it.title.lowercase() },
            )
        )
}
