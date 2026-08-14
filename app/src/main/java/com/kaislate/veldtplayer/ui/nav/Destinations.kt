// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.net.Uri

/**
 * Route parameters are NORMALIZED keys (LibraryKeys.normalize), never display names — **with one
 * deliberate exception: folder keys are BYTE-EXACT.** Normalising is right for tags, where
 * "Beatles" and "beatles" ARE one artist, and wrong for paths, where the filesystem is the
 * authority and it is case-sensitive. A future reader who "fixes" this inconsistency by
 * lowercasing a folder key would silently merge `Music/beck` and `Music/Beck`, which are two real
 * directories on ext4/f2fs. See FolderTree's KDoc.
 */
object Destinations {
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ARTISTS = "artists"
    const val PLAYLISTS = "playlists"
    const val FOLDERS = "folders"
    const val SEARCH = "search"
    const val NOW_PLAYING = "nowplaying"
    const val SETTINGS = "settings"
    const val NOTICES = "notices"
    const val ACCOUNTS = "accounts"

    const val ARG_KEY = "key"
    const val ALBUM_DETAIL = "album/{$ARG_KEY}"
    const val ARTIST_DETAIL = "artist/{$ARG_KEY}"
    const val FOLDER_DETAIL = "folder/{$ARG_KEY}"

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

    /**
     * A folder route's key is **BYTE-EXACT**, not `LibraryKeys.normalize`d.
     *
     * `Uri.encode` is mandatory rather than defensive: folder keys contain `/` by construction and
     * legally contain `%`, `#` and spaces.
     */
    fun folder(key: String) = "folder/${Uri.encode(key)}"
}
