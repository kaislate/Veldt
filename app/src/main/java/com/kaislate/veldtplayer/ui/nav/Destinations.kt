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
    const val PLAYLISTS = "playlists" // reserved for P1.4; no destination registered yet
    const val SEARCH = "search"
    const val NOW_PLAYING = "nowplaying"

    const val ARG_KEY = "key"
    const val ALBUM_DETAIL = "album/{$ARG_KEY}"
    const val ARTIST_DETAIL = "artist/{$ARG_KEY}"

    fun albumDetail(key: String) = "album/${Uri.encode(key)}"
    fun artistDetail(key: String) = "artist/${Uri.encode(key)}"
}
