// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.net.Uri

/**
 * Route parameters are NORMALIZED keys (LibraryKeys.normalize), never display names,
 * so "Abbey Road" and "abbey road" resolve to one destination.
 */
object Destinations {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val SEARCH = "search"
    const val NOW_PLAYING = "nowplaying"

    const val ARG_KEY = "key"
    const val ALBUM_DETAIL = "album/{$ARG_KEY}"
    const val ARTIST_DETAIL = "artist/{$ARG_KEY}"

    /**
     * A playlist is the one destination keyed by a ROW ID rather than a normalized name: two
     * playlists may legitimately share a name, and renaming one must not change the destination
     * the user is looking at.
     */
    const val ARG_ID = "id"
    const val PLAYLIST_DETAIL = "playlist/{$ARG_ID}"

    fun albumDetail(key: String) = "album/${Uri.encode(key)}"
    fun artistDetail(key: String) = "artist/${Uri.encode(key)}"
    fun playlistDetail(id: Long) = "playlist/$id"
}
