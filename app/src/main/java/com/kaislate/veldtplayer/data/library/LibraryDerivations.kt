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

    /**
     * Albums come out ALPHABETICAL BY TITLE, deliberately — not in key order.
     *
     * The compound key leads with the album artist, so sorting by it silently produced an
     * artist-major grid. That is wrong for this surface: an album tile's dominant label is
     * the TITLE, and a list ordered by a field the eye is not scanning reads as unsorted.
     * Artist-major browsing already has a home — the Artists tab — and duplicating it here
     * would cost the Albums tab its own reason to exist.
     *
     * Ties (two records genuinely called "Greatest Hits") break on the key, so the order is
     * total and stable and the pair lands adjacent under their differing captions. Untitled
     * albums sort last, matching [sortAlbumTracks]'s treatment of untagged tracks: a stray
     * unlabelled record belongs at the end of the shelf, not at the front of it.
     */
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
            .sortedWith(
                compareBy(
                    { it.name.isBlank() },
                    { it.name.lowercase() },
                    { it.key },
                )
            )

    /**
     * Artists sort by key, which for an artist IS the folded display name — so unlike
     * [deriveAlbums] this needs no separate comparator to come out alphabetical.
     */
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
