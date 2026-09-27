// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.browse

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.VeldtUri
import com.kaislate.veldtplayer.playback.queue.QueueFixtures
import com.kaislate.veldtplayer.playback.queue.SavedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * [BrowseSessionBridge]: the browse tree as Media3 items (spec §6) — art as a grantable
 * `content://` uri, the root extras, and what `onAddMediaItems`/`onSetMediaItems` put on the
 * player.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowseSessionBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun song(n: Int, sourceId: String = "local") = QueueFixtures.song(n, sourceId = sourceId)

    private val songs = listOf(song(1), song(2), song(3, sourceId = "acct"))

    private class Catalog(val songs: List<Song>, var recent: SavedQueue? = null) : BrowseCatalog {
        override suspend fun songs() = songs
        override suspend fun playlists() = emptyList<PlaylistRef>()
        override suspend fun playlistSongs(id: Long) = emptyList<Song>()
        override suspend fun recent() = recent
    }

    private val catalog = Catalog(songs)

    /** Local tracks play their content uri, server tracks their logical uri — as MusicRepository. */
    private val bridge = BrowseSessionBridge(
        context = context,
        tree = BrowseTree(catalog),
        playableUri = { s -> if (s.sourceId == "local") s.uri else VeldtUri.track(s.sourceId, s.externalId) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val car = MediaSession.ControllerInfo.createTestOnlyControllerInfo(
        "com.google.android.projection.gearhead", 0, 10_123, 0, 0, false, Bundle.EMPTY,
    )

    private fun <T> LibraryResult<T>.get(): T {
        assertEquals(LibraryResult.RESULT_SUCCESS, resultCode)
        return value!!
    }

    @Test fun `browse items carry a content art uri for the provider, decodable back to the track's art`() {
        val items = bridge.children(car, BrowseIds.SONGS, 0, 100, null).get(5, TimeUnit.SECONDS).get()
        val first = items.first()
        val art = first.mediaMetadata.artworkUri!!
        assertEquals("content", art.scheme)
        assertEquals(context.packageName + ".browseart", art.authority)
        assertEquals(songs.single { it.title == first.mediaMetadata.title.toString() }.toSongArt(), BrowseArtUri.parse(art))
        assertEquals(true, first.mediaMetadata.isPlayable)
        assertEquals(false, first.mediaMetadata.isBrowsable)
        assertEquals(first.mediaMetadata.artist, first.mediaMetadata.subtitle)
    }

    @Test fun `a non-art or foreign uri is not decoded by the provider`() {
        assertEquals(null, BrowseArtUri.parse(Uri.parse("content://x/other/1")))
        assertEquals(null, BrowseArtUri.parse(Uri.parse("veldt-art://song/1?src=a")))
    }

    @Test fun `the root says search is supported, and a recent root opts out when nothing is saved`() {
        val root = bridge.libraryRoot(car, null).get(5, TimeUnit.SECONDS)
        assertEquals(BrowseIds.ROOT, root.get().mediaId)
        assertTrue(root.params!!.extras.getBoolean("android.media.browse.SEARCH_SUPPORTED"))

        val recentParams = LibraryParams.Builder().setRecent(true).build()
        val none = bridge.libraryRoot(car, recentParams).get(5, TimeUnit.SECONDS)
        assertEquals(SessionError.ERROR_NOT_SUPPORTED, none.resultCode)

        catalog.recent = QueueFixtures.queue(size = 2, index = 1)
        val recent = bridge.libraryRoot(car, recentParams).get(5, TimeUnit.SECONDS)
        assertEquals(BrowseIds.RECENT_ROOT, recent.get().mediaId)
    }

    @Test fun `the root children limit a controller sent with its root request applies to its root`() {
        val limited = LibraryParams.Builder()
            .setExtras(Bundle().apply { putInt(MediaConstants.EXTRAS_KEY_ROOT_CHILDREN_LIMIT, 4) })
            .build()
        bridge.libraryRoot(car, limited).get(5, TimeUnit.SECONDS)
        val root = bridge.children(car, BrowseIds.ROOT, 0, 100, null).get(5, TimeUnit.SECONDS).get()
        assertEquals(4, root.size)
        assertFalse(root.any { it.mediaId == BrowseIds.RECENT })
    }

    @Test fun `an unknown parent is a bad-value error, not an empty list`() {
        val result = bridge.children(car, "nonsense", 0, 10, null).get(5, TimeUnit.SECONDS)
        assertEquals(SessionError.ERROR_BAD_VALUE, result.resultCode)
    }

    @Test fun `items the app queued with a uri are passed through untouched and at once`() {
        val own = listOf(MediaItem.Builder().setMediaId("local:x").setUri("content://media/1").build())
        val added = bridge.addMediaItems(own)
        assertTrue(added.isDone)
        assertSame(own, added.get())
        val set = bridge.setMediaItems(own, 0, 1_000L).get()
        // MediaItemsWithStartPosition copies the list; the ITEM is what must be untouched.
        assertSame(own.single(), set.mediaItems.single())
        assertEquals(1_000L, set.startPositionMs)
    }

    @Test fun `a browsed leaf is set as its whole list, as playable session items`() {
        val leaves = bridge.children(car, BrowseIds.SONGS, 0, 100, null).get(5, TimeUnit.SECONDS).get()
        val tapped = leaves[2]
        val request = listOf(MediaItem.Builder().setMediaId(tapped.mediaId).build())
        val set = bridge.setMediaItems(request, C.INDEX_UNSET, C.TIME_UNSET).get(5, TimeUnit.SECONDS)
        assertEquals(leaves.size, set.mediaItems.size)
        assertEquals(2, set.startIndex)
        assertEquals(0L, set.startPositionMs)
        // Session items, not browse items: the session mediaId, the playable uri, the private art.
        val acct = set.mediaItems.single { it.mediaId == "acct:ext-3" }
        assertEquals(VeldtUri.track("acct", "ext-3"), acct.localConfiguration?.uri.toString())
        assertEquals("veldt-art", acct.mediaMetadata.artworkUri?.scheme)
    }

    @Test fun `a voice request is searched and played`() {
        val request = listOf(
            MediaItem.Builder().setRequestMetadata(
                MediaItem.RequestMetadata.Builder().setSearchQuery("Fresh 2").build(),
            ).build(),
        )
        val set = bridge.setMediaItems(request, C.INDEX_UNSET, C.TIME_UNSET).get(5, TimeUnit.SECONDS)
        assertEquals("local:ext-2", set.mediaItems[set.startIndex].mediaId)
    }

    @Test fun `a request naming nothing fails rather than clearing the queue`() {
        val request = listOf(MediaItem.Builder().setMediaId("local:missing").build())
        val future = bridge.setMediaItems(request, 0, C.TIME_UNSET)
        val failed = runCatching { future.get(5, TimeUnit.SECONDS) }
        assertTrue(failed.isFailure)
    }
}
