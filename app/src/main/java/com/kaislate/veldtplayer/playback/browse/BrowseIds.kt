// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Every mediaId the browse tree hands out (spec §6), and their inverse.
 *
 * **A leaf's id carries its context.** A car head unit (and Assistant) plays a browsed track by
 * sending back nothing but its mediaId. Spec §6 wants that track to play IN the list it was
 * browsed in — the album, the playlist, all songs — so the id itself says which list, and where in
 * it: `play|<context>|<index>|<session mediaId>`. The index is a hint, not the identity: the list
 * is rebuilt when the id comes back, and if the library changed in between, the session mediaId
 * (`sourceId:externalId`, see `SessionMediaId`) finds the track again wherever it moved.
 *
 * **Why the parts are percent-encoded.** Album and artist keys, search queries and server
 * external ids are all free text, and free text can contain `|` or `/`. Every variable part of a
 * context is `URLEncoder`-encoded, so the separators below can only ever be separators; the
 * session mediaId goes LAST and unencoded, so splitting at the first three `|` is exact no matter
 * what it contains.
 */
object BrowseIds {

    const val ROOT = "veldt.root"

    /** The root handed to a controller asking for RECENT content (spec §6, `LibraryParams.isRecent`). */
    const val RECENT_ROOT = "veldt.recent.root"

    const val RECENT = "veldt.recent"
    const val PLAYLISTS = "veldt.playlists"
    const val ALBUMS = "veldt.albums"
    const val ARTISTS = "veldt.artists"
    const val SONGS = "veldt.songs"

    private const val ALBUM = "album/"
    private const val ARTIST = "artist/"
    private const val PLAYLIST = "playlist/"
    private const val SEARCH = "search/"
    private const val LEAF = "play|"

    fun album(key: String) = ALBUM + encode(key)
    fun artist(key: String) = ARTIST + encode(key)
    fun playlist(id: Long) = PLAYLIST + id
    fun search(query: String) = SEARCH + encode(query)

    fun leaf(context: String, index: Int, mediaId: String) = "$LEAF$context|$index|$mediaId"

    /** What a container id names, or null for an id that is not a container. */
    fun parseContainer(id: String): Container? = when {
        id == RECENT -> Container.Recent
        id == SONGS -> Container.Songs
        id.startsWith(ALBUM) -> decode(id.removePrefix(ALBUM))?.let(Container::Album)
        id.startsWith(ARTIST) -> decode(id.removePrefix(ARTIST))?.let(Container::Artist)
        id.startsWith(PLAYLIST) -> id.removePrefix(PLAYLIST).toLongOrNull()?.let(Container::Playlist)
        id.startsWith(SEARCH) -> decode(id.removePrefix(SEARCH))?.let(Container::Search)
        else -> null
    }

    /** The parts of a leaf id, or null for anything that is not one. */
    fun parseLeaf(id: String): Leaf? {
        if (!id.startsWith(LEAF)) return null
        val rest = id.removePrefix(LEAF)
        val bar1 = rest.indexOf('|')
        if (bar1 <= 0) return null
        val bar2 = rest.indexOf('|', bar1 + 1)
        if (bar2 < 0) return null
        val context = rest.substring(0, bar1)
        val index = rest.substring(bar1 + 1, bar2).toIntOrNull() ?: return null
        val mediaId = rest.substring(bar2 + 1).takeIf { it.isNotEmpty() } ?: return null
        return Leaf(context, index, mediaId)
    }

    /** A playable list: the context a leaf plays in, and the container that lists it. */
    sealed interface Container {
        data object Recent : Container
        data object Songs : Container
        data class Album(val key: String) : Container
        data class Artist(val key: String) : Container
        data class Playlist(val id: Long) : Container
        data class Search(val query: String) : Container
    }

    data class Leaf(val context: String, val index: Int, val mediaId: String)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
}
