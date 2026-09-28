// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PlaybackConnection.playerRequests] — the one-shot "show the player" event behind the owner's
 * "when you start to play a song it should immediately go to the playing screen".
 *
 * **What is pinned is the NARROWNESS as much as the event.** Exactly one per [PlaybackConnection.playFrom];
 * none from the commands that must never yank the screen away — appending to the queue (the user
 * is still building it), the transport, jumping within the queue from the player's own queue
 * sheet. The paths that do not come through this class at all (Android Auto, the widget, the
 * notification, media buttons, the restored queue) cannot emit by construction and have nothing
 * to assert here.
 *
 * Every command below needs a `MediaController` to actually play, and under Robolectric none ever
 * connects — the commands park in the connection's pending queue. That is fine for this seam: the
 * event is emitted by the connection itself, before and regardless of the controller, which is
 * what makes it observable here at all.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the sibling suites are.
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRequestsTest {

    /** A DAO nothing here reads: the connection only needs a repository to construct. */
    private class EmptySongDao : SongDao {
        override fun observeAllSongs(): Flow<List<SongEntity>> = emptyFlow()
        override suspend fun findIdBySourceKey(sourceId: String, externalId: String): Long? = null
        override suspend fun insertReplacing(row: SongEntity) = Unit
        override suspend fun getAllSongs(): List<SongEntity> = emptyList()
        override suspend fun getSongsByAlbum(album: String): List<SongEntity> = emptyList()
        override suspend fun getSongsByArtist(artist: String): List<SongEntity> = emptyList()
        override suspend fun search(pattern: String): List<SongEntity> = emptyList()
        override fun observeSearch(pattern: String): Flow<List<SongEntity>> = emptyFlow()
        override suspend fun getIndex(sourceId: String): List<IndexEntry> = emptyList()
        override suspend fun deleteByExternalIds(sourceId: String, externalIds: List<String>) = Unit
        override suspend fun getBySource(sourceId: String): List<SongEntity> = emptyList()
        override suspend fun deleteBySource(sourceId: String) = Unit
        override suspend fun accountRowCount(sourceId: String) = 0
        override suspend fun clear() = Unit
    }

    private val localSource = object : LibrarySource {
        override val id = "test"
        override suspend fun listSongs() = emptyList<Song>()
        override suspend fun listAlbums() = emptyList<Album>()
        override suspend fun listArtists() = emptyList<Artist>()
        override suspend fun search(query: String) = emptyList<Song>()
        override fun resolvePlayableUri(song: Song) = song.uri
        override fun stableKey(song: Song) = song.uri
    }

    private lateinit var connection: PlaybackConnection

    @Before fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        val repo = MusicRepository(
            EmptySongDao(),
            SourceRegistry(emptySet()),
            localSource,
            context,
            emptyFlow(),
        )
        connection = PlaybackConnection(context, repo, NetworkReturn.NONE)
    }

    private fun song(id: Long) = Song(
        id = id,
        sourceId = "test",
        externalId = "e$id",
        uri = "content://media/external/audio/media/$id",
        filePath = null,
        relativeKey = "external_primary:Music/$id.mp3",
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 180_000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private val songs = listOf(song(1), song(2), song(3))

    /** Collects every request made while [block] runs. Unconfined, so the collector is already
     *  subscribed when [block] starts — the flow has no replay, so a late one would see nothing. */
    private fun requestsDuring(block: () -> Unit): Int {
        var count = 0
        runTest(UnconfinedTestDispatcher()) {
            val job = launch { connection.playerRequests.collect { count++ } }
            block()
            job.cancel()
        }
        return count
    }

    @Test fun `playFrom emits exactly one player request`() {
        assertEquals(1, requestsDuring { connection.playFrom(songs, 1) })
    }

    @Test fun `each playFrom is its own request`() {
        assertEquals(
            2,
            requestsDuring {
                connection.playFrom(songs, 0)
                connection.playFrom(songs, 2)
            },
        )
    }

    /** A call that plays nothing opens nothing: the sheet would open over an empty queue. */
    @Test fun `playFrom with nothing to play emits nothing`() {
        assertEquals(0, requestsDuring { connection.playFrom(emptyList(), 0) })
    }

    /**
     * Appending — "Add to queue" / "Play next" style verbs all go through addToQueue — never
     * opens the player, INCLUDING the case where the queue was empty and the append therefore
     * starts playback: the user asked to queue something, not to be taken anywhere.
     */
    @Test fun `addToQueue emits nothing, whether or not it starts playback`() {
        assertEquals(
            0,
            requestsDuring {
                connection.addToQueue(songs) // empty queue: this one starts playback
                connection.addToQueue(listOf(song(4))) // non-empty: a plain append
            },
        )
    }

    /** The transport and the player's own queue sheet are used FROM the player (or the
     *  mini-player); re-requesting it from there would be noise at best. */
    @Test fun `transport and queue jumps emit nothing`() {
        connection.playFrom(songs, 0)
        assertEquals(
            0,
            requestsDuring {
                connection.toggle()
                connection.next()
                connection.previous()
                connection.skipToQueueIndex(2)
                connection.seekTo(1_000L)
            },
        )
    }
}
