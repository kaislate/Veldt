// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK so the DAO test starts under targetSdk 36.
@Config(sdk = [34])
class SongDaoTest {
    private lateinit var db: VeldtDatabase
    private lateinit var dao: SongDao

    private fun entity(
        id: Long,
        title: String,
        album: String = "Al",
        modified: Long = 100,
        relativeKey: String? = null,
    ) =
        SongEntity(
            id = id, uri = "content://$id", filePath = null, relativeKey = relativeKey,
            title = title, artist = "A",
            album = album, albumArtist = null, trackNumber = null, discNumber = null, year = null,
            durationMs = 1000, dateModifiedSec = modified, hasEmbeddedArt = false,
        )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), VeldtDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.songDao()
    }

    @After fun tearDown() = db.close()

    @Test fun insert_then_queryAll_returnsRows() = runTest {
        dao.upsertAll(listOf(entity(1, "Beta"), entity(2, "Alpha")))
        val all = dao.getAllSongs()
        assertEquals(2, all.size)
        assertEquals("Alpha", all.first().title) // ORDER BY title COLLATE NOCASE
    }

    @Test fun upsert_replacesOnConflict() = runTest {
        dao.upsertAll(listOf(entity(1, "Old")))
        dao.upsertAll(listOf(entity(1, "New")))
        assertEquals("New", dao.getAllSongs().single().title)
    }

    /**
     * The projection is the diff's only input, so it has to carry the location too — see
     * [IndexEntry]. A non-null `relativeKey` is used deliberately: with null on both sides this
     * assertion would pass identically whether or not the column is in the SELECT.
     */
    @Test fun getIndex_returnsIdDateAndRelativeKey() = runTest {
        dao.upsertAll(
            listOf(entity(7, "x", modified = 555, relativeKey = "external_primary:Music/x.mp3"))
        )
        assertEquals(
            IndexEntry(7, 555, "external_primary:Music/x.mp3"),
            dao.getIndex().single(),
        )
    }

    /** A provider that withholds the location columns still projects, as null, not as a crash. */
    @Test fun getIndex_toleratesANullRelativeKey() = runTest {
        dao.upsertAll(listOf(entity(8, "y", modified = 1, relativeKey = null)))
        assertEquals(IndexEntry(8, 1, null), dao.getIndex().single())
    }

    @Test fun searchAndAlbumQuery_work() = runTest {
        dao.upsertAll(listOf(entity(1, "Hello", album = "AlbA"), entity(2, "World", album = "AlbB")))
        assertEquals(1, dao.search("%Hello%").size)
        assertEquals(1, dao.getSongsByAlbum("AlbA").size)
    }

    @Test fun observeAllSongs_emits() = runTest {
        dao.upsertAll(listOf(entity(1, "x")))
        assertEquals(1, dao.observeAllSongs().first().size)
    }

    @Test fun deleteByIds_and_clear() = runTest {
        dao.upsertAll(listOf(entity(1, "x"), entity(2, "y")))
        dao.deleteByIds(listOf(1))
        assertEquals(1, dao.getAllSongs().size)
        dao.clear()
        assertEquals(0, dao.getAllSongs().size)
    }
}
