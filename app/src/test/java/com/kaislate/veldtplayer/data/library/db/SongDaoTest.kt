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
     *
     * The projected identity is `externalId`, NOT [SongEntity.id]: the diff must never see the
     * app-internal handle, which becomes a Room surrogate in Task 3 and would then churn the whole
     * library on the first scan after the flip. The fixture's id (`7`) and externalId (`"ms-9007"`)
     * are deliberately unequal (GC 14), so a projection of the wrong column cannot typecheck, let
     * alone pass.
     */
    @Test fun getIndex_returnsExternalIdDateAndRelativeKey() = runTest {
        dao.upsertAll(
            listOf(
                entity(
                    7, "x", externalId = "ms-9007", modified = 555,
                    relativeKey = "external_primary:Music/x.mp3",
                )
            )
        )
        assertEquals(
            IndexEntry("ms-9007", 555, "external_primary:Music/x.mp3"),
            dao.getIndex("test-source").single(),
        )
    }

    /** A provider that withholds the location columns still projects, as null, not as a crash. */
    @Test fun getIndex_toleratesANullRelativeKey() = runTest {
        dao.upsertAll(listOf(entity(8, "y", externalId = "ms-9008", modified = 1, relativeKey = null)))
        assertEquals(IndexEntry("ms-9008", 1, null), dao.getIndex("test-source").single())
    }

    /**
     * Source scoping on the READ side, as the non-collapse of a named pair. Two sources each hold
     * one row; each `getIndex` call must see its own row and only its own.
     *
     * Both directions are asserted, not one: `getIndex("alpha") == ["ms-1"]` alone is satisfied by a
     * query that ignores its argument whenever `alpha` happens to be the only row it returns, and by
     * an unscoped query the moment the fixture has a single row. Asserting the pair makes an
     * argument-ignoring query fail on whichever call it answers wrongly, and the failure message
     * names the row that leaked across the boundary.
     *
     * What this must NOT collapse: **another source's index rows into this source's scan input.**
     * They arrive as `removed` and the scan deletes a library it does not own.
     */
    @Test fun `getIndex returns only the requested source's rows`() = runTest {
        dao.upsertAll(
            listOf(
                entity(id = 1, sourceId = "alpha", externalId = "ms-1", title = "a", relativeKey = "k1"),
                entity(id = 2, sourceId = "beta", externalId = "ms-2", title = "b", relativeKey = "k2"),
            )
        )
        assertEquals(listOf("ms-1"), dao.getIndex("alpha").map { it.externalId })
        assertEquals(listOf("ms-2"), dao.getIndex("beta").map { it.externalId })
    }

    /**
     * Source scoping on the WRITE side — the destructive half, and the one that costs data when it
     * is wrong. Two sources deliberately share the externalId `"42"` (a Subsonic track `42` and a
     * MediaStore `_ID` `42` are different songs, which is why the unique index is a PAIR), and one
     * source deletes it.
     *
     * The survivor is asserted as the pair `(id, sourceId)` rather than as a count: `size == 1`
     * would pass just as well if the query had deleted the WRONG row of the two, which is the
     * failure that silently empties a user's remote library during a local rescan.
     */
    @Test fun `deleteByExternalIds spares another source's row with the same externalId`() = runTest {
        dao.upsertAll(
            listOf(
                entity(id = 1, sourceId = "alpha", externalId = "42", title = "alpha keeps nothing"),
                entity(id = 2, sourceId = "beta", externalId = "42", title = "beta keeps this"),
            )
        )
        dao.deleteByExternalIds("alpha", listOf("42"))
        val survivor = dao.getAllSongs().single()
        assertEquals(2L to "beta", survivor.id to survivor.sourceId)
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

    /**
     * The survivor is named, not counted: the fixture's ids and externalIds are unequal (GC 14), so
     * a query that deleted by the app-internal handle instead of the source-native id would take
     * the wrong row and `size == 1` would not notice.
     */
    @Test fun deleteByExternalIds_and_clear() = runTest {
        dao.upsertAll(
            listOf(entity(1, "x", externalId = "ms-9001"), entity(2, "y", externalId = "ms-9002"))
        )
        dao.deleteByExternalIds("test-source", listOf("ms-9001"))
        assertEquals(listOf("ms-9002"), dao.getAllSongs().map { it.externalId })
        dao.clear()
        assertEquals(0, dao.getAllSongs().size)
    }
}
