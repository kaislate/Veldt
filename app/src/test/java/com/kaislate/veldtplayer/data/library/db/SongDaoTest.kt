// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        title: String = "T",
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

    // ------------------------------------------------- the surrogate, and the id-stable upsert

    /**
     * **The property `upsertBySourceKey` exists for, and the one that costs user data when it is
     * wrong.** A row's content changes — a re-tag, a move — and it is upserted again. Its surrogate
     * must be the *same number* afterwards, because `playlist_entries.songId`, and every art or
     * palette cache keyed on it, are holding that number.
     *
     * A bare `@Insert(REPLACE)` cannot do this. With an `AUTOINCREMENT` PK it resolves the
     * `(sourceId, externalId)` unique-index conflict by DELETE-and-REINSERT, and the reinserted row
     * draws a **fresh** id. Every scan that touched a row would silently invalidate every playlist
     * entry pointing at it — on a background worker, with no user action to blame it on.
     *
     * Asserted as the pair `(id, title)` in one assertion, so the failure message IS the trade the
     * defect makes: the title updated (the upsert "worked") while the id moved underneath it. The
     * id alone would pass for an upsert that did nothing at all.
     */
    @Test fun `re-upserting a changed row keeps its surrogate id`() = runTest {
        dao.upsertBySourceKey(
            listOf(entity(id = Song.UNSAVED, externalId = "ms-9001", title = "before", modified = 1L))
        )
        val assigned = dao.getAllSongs().single().id
        dao.upsertBySourceKey(
            listOf(entity(id = Song.UNSAVED, externalId = "ms-9001", title = "after", modified = 2L))
        )
        val after = dao.getAllSongs().single()
        assertEquals(assigned to "after", after.id to after.title)
    }

    /**
     * `AUTOINCREMENT`, stated as the behaviour it buys rather than as the keyword.
     *
     * Deleting the **MAX** row is the case that tells the two apart: plain `INTEGER PRIMARY KEY`
     * assigns `max(rowid) + 1`, so it would hand `ms-1`'s freed id straight to `ms-2`;
     * `AUTOINCREMENT` never reissues. That matters because a `playlist_entries.songId` (or an art
     * cache key) can outlive the row it names — the user deletes a file, the scan removes it, the
     * next scan adds a different one. With reissue that stale reference silently resolves to the
     * **wrong song**; without it, it resolves to nothing and the entry greys out honestly.
     *
     * `assertNotEquals` would be too weak here: monotonicity, not mere difference, is the property
     * — and only `>` distinguishes it from an id space that wandered.
     */
    @Test fun `a freed surrogate id is never reissued to a later row`() = runTest {
        dao.upsertBySourceKey(listOf(entity(id = Song.UNSAVED, externalId = "ms-1")))
        val first = dao.getAllSongs().single().id
        dao.deleteByExternalIds("test-source", listOf("ms-1"))
        dao.upsertBySourceKey(listOf(entity(id = Song.UNSAVED, externalId = "ms-2")))
        val second = dao.getAllSongs().single().id
        assertTrue("surrogate $second reissued after $first was freed", second > first)
    }

    /**
     * The escape hatch every fixture in this repo rests on: an id supplied explicitly is stored
     * verbatim, and only [Song.UNSAVED] means "assign me one". Room's `autoGenerate` treats `0` as
     * the not-set signal, which is exactly why the sentinel's value is `0` and not `-1`.
     *
     * Without this, every seeded row would silently get a different id from the one the test named,
     * and assertions written against literal ids would be asserting nothing.
     */
    @Test fun `an explicitly-set id on first insert is preserved`() = runTest {
        dao.upsertBySourceKey(listOf(entity(id = 7, externalId = "ms-7")))
        assertEquals(7L, dao.getAllSongs().single().id)
    }

    @Test fun insert_then_queryAll_returnsRows() = runTest {
        dao.upsertBySourceKey(
            listOf(entity(1, "Beta", externalId = "ms-9001"), entity(2, "Alpha", externalId = "ms-9002"))
        )
        val all = dao.getAllSongs()
        assertEquals(2, all.size)
        assertEquals("Alpha", all.first().title) // ORDER BY title COLLATE NOCASE
    }

    @Test fun upsert_replacesOnConflict() = runTest {
        dao.upsertBySourceKey(listOf(entity(1, "Old", externalId = "ms-9001")))
        dao.upsertBySourceKey(listOf(entity(1, "New", externalId = "ms-9001")))
        assertEquals("New", dao.getAllSongs().single().title)
    }

    /**
     * The unique index, stated as the non-collapse it exists to force. Two rows with **different**
     * app-internal ids claim the **same** `(sourceId, externalId)` — the same file rediscovered
     * under a reissued `_ID`. `OnConflictStrategy.REPLACE` resolves that against the unique index by
     * replacing the older row, so exactly the newer one survives. Without `unique = true` on the
     * index BOTH rows survive and the library shows the track twice; that is what the negative
     * control proves. Asserted as a pair (id AND title) so the failure message names which row won.
     *
     * **This goes through [SongDao.insertReplacing] deliberately, not through
     * [SongDao.upsertBySourceKey], and the distinction is what keeps it falsifiable.**
     * `upsertBySourceKey` resolves this collision itself, by looking the natural key up first — so
     * routed through it, dropping `unique = true` from the index would leave this test **green**
     * and the index would be pinned by nothing at all. The raw insert is the only caller that lets
     * the index be the thing under test.
     */
    @Test fun `two rows may not share one sourceId-externalId pair`() = runTest {
        dao.insertReplacing(entity(id = 1, externalId = "ms-9001", title = "first"))
        dao.insertReplacing(entity(id = 2, externalId = "ms-9001", title = "second"))
        assertEquals(
            listOf(2L to "second"),
            dao.getAllSongs().map { it.id to it.title },
        )
    }

    /**
     * The same collision through the production path, and it resolves the **other** way: the
     * surviving row keeps the id it already had and takes the new content. That asymmetry with the
     * test above is the entire deliverable of this task — a raw REPLACE re-numbers the row, and
     * `upsertBySourceKey` does not.
     *
     * Asserted as a pair, and both halves are load bearing: `1L` alone would pass for an upsert
     * that ignored the second row completely, and `"second"` alone is what a bare REPLACE also
     * produces. Only together do they name the case.
     */
    @Test fun `an upsert onto an existing natural key keeps the id and takes the content`() =
        runTest {
            dao.insertReplacing(entity(id = 1, externalId = "ms-9001", title = "first"))
            dao.upsertBySourceKey(listOf(entity(id = 2, externalId = "ms-9001", title = "second")))
            assertEquals(
                listOf(1L to "second"),
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
        dao.upsertBySourceKey(
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
        dao.upsertBySourceKey(
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
        dao.upsertBySourceKey(listOf(entity(8, "y", externalId = "ms-9008", modified = 1, relativeKey = null)))
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
        dao.upsertBySourceKey(
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
        dao.upsertBySourceKey(
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
        dao.upsertBySourceKey(
            listOf(
                entity(1, "Hello", externalId = "ms-9001", album = "AlbA"),
                entity(2, "World", externalId = "ms-9002", album = "AlbB"),
            )
        )
        assertEquals(1, dao.search("%Hello%").size)
        assertEquals(1, dao.getSongsByAlbum("AlbA").size)
    }

    @Test fun observeAllSongs_emits() = runTest {
        dao.upsertBySourceKey(listOf(entity(1, "x", externalId = "ms-9001")))
        assertEquals(1, dao.observeAllSongs().first().size)
    }

    /**
     * The survivor is named, not counted: the fixture's ids and externalIds are unequal (GC 14), so
     * a query that deleted by the app-internal handle instead of the source-native id would take
     * the wrong row and `size == 1` would not notice.
     */
    @Test fun deleteByExternalIds_and_clear() = runTest {
        dao.upsertBySourceKey(
            listOf(entity(1, "x", externalId = "ms-9001"), entity(2, "y", externalId = "ms-9002"))
        )
        dao.deleteByExternalIds("test-source", listOf("ms-9001"))
        assertEquals(listOf("ms-9002"), dao.getAllSongs().map { it.externalId })
        dao.clear()
        assertEquals(0, dao.getAllSongs().size)
    }
}
