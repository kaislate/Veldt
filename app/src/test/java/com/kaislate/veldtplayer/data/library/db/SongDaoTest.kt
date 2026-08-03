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

    /**
     * [externalId] is a **required** parameter and is never `id.toString()` (Global Constraint 14).
     * The whole point of this phase is that the row's app-internal handle and its source-native
     * identity are different things; a fixture where they are equal would let code that collapses
     * one into the other pass. [sourceId] defaults because most tests here are single-source — the
     * two that are not name both sources explicitly.
     */
    private fun entity(
        id: Long,
        title: String,
        externalId: String,
        sourceId: String = "test-source",
        album: String = "Al",
        modified: Long = 100,
        relativeKey: String? = null,
    ) =
        SongEntity(
            id = id, sourceId = sourceId, externalId = externalId,
            uri = "content://$id", filePath = null, relativeKey = relativeKey,
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
        dao.upsertAll(
            listOf(entity(1, "Beta", externalId = "ms-9001"), entity(2, "Alpha", externalId = "ms-9002"))
        )
        val all = dao.getAllSongs()
        assertEquals(2, all.size)
        assertEquals("Alpha", all.first().title) // ORDER BY title COLLATE NOCASE
    }

    @Test fun upsert_replacesOnConflict() = runTest {
        dao.upsertAll(listOf(entity(1, "Old", externalId = "ms-9001")))
        dao.upsertAll(listOf(entity(1, "New", externalId = "ms-9001")))
        assertEquals("New", dao.getAllSongs().single().title)
    }

    /**
     * The unique index, stated as the non-collapse it exists to force. Two rows with **different**
     * app-internal ids claim the **same** `(sourceId, externalId)` — the same file rediscovered
     * under a reissued `_ID`. `OnConflictStrategy.REPLACE` resolves that against the unique index by
     * replacing the older row, so exactly the newer one survives. Without `unique = true` on the
     * index BOTH rows survive and the library shows the track twice; that is what the negative
     * control proves. Asserted as a pair (id AND title) so the failure message names which row won.
     */
    @Test fun `two rows may not share one sourceId-externalId pair`() = runTest {
        dao.upsertAll(listOf(entity(id = 1, externalId = "ms-9001", title = "first")))
        dao.upsertAll(listOf(entity(id = 2, externalId = "ms-9001", title = "second")))
        assertEquals(
            listOf(2L to "second"),
            dao.getAllSongs().map { it.id to it.title },
        )
    }

    /**
     * The other half of the same property, and the reason the index is a PAIR rather than
     * `externalId` alone: two sources may legitimately hand out the same source-native id. A
     * Subsonic track `42` and a MediaStore `_ID` `42` are different songs. Collapsing the source
     * dimension merges these two rows into one; asserted as a pair so the failure message IS
     * that merge.
     */
    @Test fun `the same externalId under two sources is two distinct rows`() = runTest {
        dao.upsertAll(
            listOf(
                entity(id = 1, sourceId = "alpha", externalId = "42", title = "alpha's"),
                entity(id = 2, sourceId = "beta", externalId = "42", title = "beta's"),
            )
        )
        assertEquals(
            listOf(1L to "alpha's", 2L to "beta's"),
            dao.getAllSongs().sortedBy { it.id }.map { it.id to it.title },
        )
    }

    /**
     * The projection is the diff's only input, so it has to carry the location too — see
     * [IndexEntry]. A non-null `relativeKey` is used deliberately: with null on both sides this
     * assertion would pass identically whether or not the column is in the SELECT.
     */
    @Test fun getIndex_returnsIdDateAndRelativeKey() = runTest {
        dao.upsertAll(
            listOf(
                entity(
                    7, "x", externalId = "ms-9007", modified = 555,
                    relativeKey = "external_primary:Music/x.mp3",
                )
            )
        )
        assertEquals(
            IndexEntry(7, 555, "external_primary:Music/x.mp3"),
            dao.getIndex().single(),
        )
    }

    /** A provider that withholds the location columns still projects, as null, not as a crash. */
    @Test fun getIndex_toleratesANullRelativeKey() = runTest {
        dao.upsertAll(listOf(entity(8, "y", externalId = "ms-9008", modified = 1, relativeKey = null)))
        assertEquals(IndexEntry(8, 1, null), dao.getIndex().single())
    }

    @Test fun searchAndAlbumQuery_work() = runTest {
        dao.upsertAll(
            listOf(
                entity(1, "Hello", externalId = "ms-9001", album = "AlbA"),
                entity(2, "World", externalId = "ms-9002", album = "AlbB"),
            )
        )
        assertEquals(1, dao.search("%Hello%").size)
        assertEquals(1, dao.getSongsByAlbum("AlbA").size)
    }

    @Test fun observeAllSongs_emits() = runTest {
        dao.upsertAll(listOf(entity(1, "x", externalId = "ms-9001")))
        assertEquals(1, dao.observeAllSongs().first().size)
    }

    @Test fun deleteByIds_and_clear() = runTest {
        dao.upsertAll(
            listOf(entity(1, "x", externalId = "ms-9001"), entity(2, "y", externalId = "ms-9002"))
        )
        dao.deleteByIds(listOf(1))
        assertEquals(1, dao.getAllSongs().size)
        dao.clear()
        assertEquals(0, dao.getAllSongs().size)
    }
}
