// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistRepository
import com.kaislate.veldtplayer.playback.queue.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn

/**
 * [BrowseCatalog] over the app's own repositories.
 *
 * **The library is kept warm, briefly.** A head unit pages through "Songs" one request per page,
 * and every page re-derives from the whole library; re-running the Room query and the hidden-folder
 * filter for each would be most of the cost. `shareIn` keeps ONE subscription alive for
 * [KEEP_WARM_MS] after the last read, so a browsing session reuses it, and drops it — and the
 * cached list, `replayExpirationMillis = 0` — once the car goes quiet, so a stale library is never
 * served to the next session and nothing is held while nobody browses. While it is alive it is the
 * live flow: a scan that lands mid-browse is what the next page sees.
 *
 * The repositories arrive as providers (the service's Hilt `Lazy`s) and are first touched on the
 * first browse request, so a service that nobody browses never builds them for this.
 */
class LibraryBrowseCatalog(
    private val repo: () -> MusicRepository,
    private val playlistRepo: () -> PlaylistRepository,
    private val scope: CoroutineScope,
    private val recentQueue: () -> SavedQueue?,
) : BrowseCatalog {

    private val library by lazy {
        repo().songs().shareIn(
            scope,
            SharingStarted.WhileSubscribed(stopTimeoutMillis = KEEP_WARM_MS, replayExpirationMillis = 0),
            replay = 1,
        )
    }

    override suspend fun songs(): List<Song> = library.first()

    override suspend fun playlists(): List<PlaylistRef> =
        playlistRepo().observe().first().map { PlaylistRef(it.id, it.name) }

    override suspend fun playlistSongs(id: Long): List<Song> =
        playlistRepo().resolve(id).mapNotNull { it.song }

    override suspend fun recent(): SavedQueue? = recentQueue()

    private companion object {
        const val KEEP_WARM_MS = 30_000L
    }
}
