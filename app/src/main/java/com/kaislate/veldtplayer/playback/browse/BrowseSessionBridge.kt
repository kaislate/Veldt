// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.queue.toMediaItem
import com.kaislate.veldtplayer.playback.sessionMediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The Media3 side of the browse tree (spec §6): each `MediaLibrarySession.Callback` browse and
 * item-resolution method, as a future over [BrowseTree]. `PlaybackService.LibraryCallback`
 * delegates here and does nothing else, so the callback stays a list of one-liners and the tree
 * stays pure.
 *
 * Everything runs on [scope] (a background dispatcher): paging "Songs" re-sorts the library, and
 * none of it belongs on the main thread the player lives on. Every future completes — a failure
 * becomes an exceptional completion or an error `LibraryResult`, never a hang, because a head unit
 * waiting on a browse result shows a spinner for as long as it waits.
 */
class BrowseSessionBridge(
    private val context: Context,
    private val tree: BrowseTree,
    private val playableUri: (Song) -> String,
    private val scope: CoroutineScope,
) {
    private val artAuthority = BrowseArtUri.authority(context)

    /** Each controller's `EXTRAS_KEY_ROOT_CHILDREN_LIMIT`, from its root request; see
     *  [BrowseTree.children]. Keyed by package and uid, the identity a `ControllerInfo` carries. */
    private val rootLimits = ConcurrentHashMap<String, Int>()

    fun libraryRoot(
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        params?.extras?.getInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 0)
            ?.takeIf { it > 0 }
            ?.let { rootLimits[keyOf(browser)] = it }
        if (params?.isRecent == true) {
            // Spec §6: a recent root holding the resumption item — or, with nothing saved, the
            // same opt-out resumption gives.
            val recent = tree.recentRoot() ?: return@future LibraryResult.ofError(SessionError.ERROR_NOT_SUPPORTED)
            LibraryResult.ofItem(mediaItemOf(recent, browser), params)
        } else {
            LibraryResult.ofItem(mediaItemOf(tree.root(), browser), rootParams(params))
        }
    }

    fun children(
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val nodes = tree.children(parentId, page, pageSize, rootLimits[keyOf(browser)])
            ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        LibraryResult.ofItemList(nodes.map { mediaItemOf(it, browser) }, params)
    }

    fun item(browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        future {
            val node = tree.item(mediaId) ?: return@future LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            LibraryResult.ofItem(mediaItemOf(node, browser), null)
        }

    /** `onSearch`: counts the results and tells [browser] they are ready, as Media3's contract asks. */
    fun search(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = future {
        val count = tree.searchCount(query)
        withContext(Dispatchers.Main) { session.notifySearchResultChanged(browser, query, count, params) }
        LibraryResult.ofVoid()
    }

    fun searchResult(
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        LibraryResult.ofItemList(tree.search(query, page, pageSize).map { mediaItemOf(it, browser) }, params)
    }

    /**
     * `onAddMediaItems`: bare mediaIds and voice queries resolved to playable items (spec §6).
     *
     * Items that already carry a uri — every item the app's own `PlaybackConnection` sends — are
     * returned untouched and at once, which is exactly Media3's default behaviour; only requests
     * that NEED resolving take the trip through the tree.
     */
    fun addMediaItems(items: List<MediaItem>): ListenableFuture<List<MediaItem>> {
        if (items.all { it.localConfiguration != null }) return Futures.immediateFuture(items)
        return future { tree.resolveAdd(requestsOf(items)).mapNotNull { mediaItemOf(it, items) } }
    }

    /**
     * `onSetMediaItems`: as [addMediaItems], but a single browsed item plays IN CONTEXT (spec §6) —
     * see [BrowseTree.resolvePlay]. Nothing resolvable fails the future, which leaves the player's
     * current queue alone instead of clearing it for a request that named nothing.
     */
    fun setMediaItems(
        items: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (items.all { it.localConfiguration != null }) {
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, startIndex, startPositionMs))
        }
        return future {
            val plan = tree.resolvePlay(requestsOf(items), startIndex, startPositionMs)
                ?: throw IllegalArgumentException("Nothing playable in the request")
            val resolved = plan.entries.map { mediaItemOf(it, items) }
            // An entry whose source vanished between resolution and here drops out; the start
            // index follows the entry it pointed at.
            val start = resolved.take(plan.startIndex).count { it != null }
            val playable = resolved.filterNotNull()
            if (playable.isEmpty()) throw IllegalArgumentException("Nothing playable in the request")
            MediaSession.MediaItemsWithStartPosition(playable, start.coerceAtMost(playable.lastIndex), plan.startPositionMs)
        }
    }

    // ---------- mapping ----------

    private fun requestsOf(items: List<MediaItem>): List<PlayRequest> = items.map {
        PlayRequest(
            mediaId = it.mediaId,
            searchQuery = it.requestMetadata.searchQuery,
            hasUri = it.localConfiguration != null,
        )
    }

    /** A resolved entry as the player item: the same `sessionMediaItem` the app itself queues. */
    private fun mediaItemOf(entry: PlayEntry, request: List<MediaItem>): MediaItem? = when (entry) {
        is PlayEntry.Library -> runCatching { sessionMediaItem(entry.song, playableUri(entry.song)) }.getOrNull()
        is PlayEntry.Saved -> entry.item.toMediaItem()
        is PlayEntry.Passthrough -> request.getOrNull(entry.requestIndex)
    }

    /**
     * A browse node as a Media3 item. Its artwork is a [BrowseArtUri] with a READ grant for
     * [browser] — the head unit opens it from its own process (see [BrowseArtProvider]). The app's
     * own controller needs no grant, and a grant that fails (a controller whose package cannot be
     * named) only costs that item its picture.
     */
    private fun mediaItemOf(node: BrowseNode, browser: MediaSession.ControllerInfo): MediaItem {
        val artUri = node.art?.let { BrowseArtUri.of(artAuthority, it) }
        if (artUri != null && browser.packageName != context.packageName) {
            runCatching {
                context.grantUriPermission(browser.packageName, artUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        return MediaItem.Builder()
            .setMediaId(node.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(node.title)
                    .setSubtitle(node.subtitle)
                    .apply { if (node.playable) setArtist(node.subtitle) }
                    .setIsBrowsable(node.browsable)
                    .setIsPlayable(node.playable)
                    .setMediaType(node.mediaType)
                    .setArtworkUri(artUri)
                    .apply { node.durationMs?.let(::setDurationMs) }
                    .build(),
            )
            .build()
    }

    /** The root's extras: `SEARCH_SUPPORTED` is what makes a head unit show its search button. */
    private fun rootParams(requested: LibraryParams?): LibraryParams =
        LibraryParams.Builder()
            .setExtras(Bundle().apply { putBoolean(SEARCH_SUPPORTED, true) })
            .setOffline(requested?.isOffline ?: false)
            .build()

    private fun keyOf(browser: MediaSession.ControllerInfo) = "${browser.packageName}/${browser.uid}"

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result = SettableFuture.create<T>()
        val job = scope.launch {
            try {
                result.set(block())
            } catch (cancelled: CancellationException) {
                result.setException(cancelled)
                throw cancelled
            } catch (t: Throwable) {
                result.setException(t)
            }
        }
        job.invokeOnCompletion { cause -> if (cause != null) result.setException(cause) }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return result
    }

    private companion object {
        /** `MediaBrowserCompat`'s root extra; Media3 1.8 has no constant for it. */
        const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
    }
}
