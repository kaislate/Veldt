// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK so the DAO test starts under targetSdk 36.
@Config(sdk = [34])
class PlaylistDaoTest {

    private lateinit var db: VeldtDatabase
    private lateinit var dao: PlaylistDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), VeldtDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.playlistDao()
    }

    @After fun tearDown() = db.close()

    // A test fixture may name the source literally; production code must read it from
    // LibrarySource.id (Global Constraint 2).
    private fun entry(pl: Long, pos: Int, key: String, songId: Long? = null) =
        PlaylistEntryEntity(
            id = 0, playlistId = pl, position = pos,
            sourceId = "local", sourceKey = key, songId = songId,
            sourceTitle = "T$pos", sourceArtist = "A", sourceAlbum = "Al",
        )

    @Test fun `entries come back in position order regardless of insert order`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.insertEntries(listOf(entry(pl, 2, "c"), entry(pl, 0, "a"), entry(pl, 1, "b")))
        assertEquals(listOf("a", "b", "c"), dao.observeEntries(pl).first().map { it.sourceKey })
    }

    @Test fun `deleting a playlist cascades to its entries`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.insertEntries(listOf(entry(pl, 0, "a")))
        dao.deletePlaylist(pl)
        assertEquals(emptyList<PlaylistEntryEntity>(), dao.observeEntries(pl).first())
    }

    @Test fun `an entry with no resolved song still persists and reads back`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.insertEntries(listOf(entry(pl, 0, "missing.mp3", songId = null)))
        val got = dao.observeEntries(pl).first().single()
        assertNull(got.songId)
        assertEquals("missing.mp3", got.sourceKey)
        assertEquals("T0", got.sourceTitle)
    }

    @Test fun `playlists are observable and renameable`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.rename(pl, "Renamed", 2L)
        val got = dao.observePlaylists().first().single()
        assertEquals("Renamed", got.name)
        assertEquals(1L, got.createdAt)
        assertEquals(2L, got.updatedAt)
    }

    @Test fun `replaceEntries swaps the whole sequence atomically`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.insertEntries(listOf(entry(pl, 0, "a"), entry(pl, 1, "b")))
        dao.replaceEntries(pl, listOf(entry(pl, 0, "b"), entry(pl, 1, "a")))
        assertEquals(listOf("b", "a"), dao.observeEntries(pl).first().map { it.sourceKey })
    }

    @Test fun `deleteEntry removes only that row`() = runTest {
        val pl = dao.insertPlaylist(PlaylistEntity(0, "Mix", 1L, 1L))
        dao.insertEntries(listOf(entry(pl, 0, "a"), entry(pl, 1, "b")))
        val first = dao.observeEntries(pl).first().first()
        dao.deleteEntry(first.id)
        assertEquals(listOf("b"), dao.observeEntries(pl).first().map { it.sourceKey })
    }
}
