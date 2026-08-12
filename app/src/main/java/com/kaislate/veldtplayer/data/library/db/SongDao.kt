// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    /**
     * **The only way to write a song row.** Insert each of [rows], carrying an existing row's
     * surrogate [SongEntity.id] through unchanged when `(sourceId, externalId)` already names one.
     *
     * ## Why this is a transaction and not an `@Insert(REPLACE)`
     *
     * A bare `@Insert(OnConflictStrategy.REPLACE)` against an `autoGenerate` primary key resolves
     * the `(sourceId, externalId)` unique-index conflict by **DELETE-and-REINSERT**. The reinserted
     * row draws a *fresh* surrogate. So every re-upsert of a **changed** row silently renumbers it —
     * and every `playlist_entries.songId` cached against the old number goes stale on **every scan
     * that touches the row**, on a background worker, with no user action to associate the damage
     * with. Looking the natural key up first and copying its id onto the incoming row is what makes
     * the write an update in effect rather than only in name.
     *
     * The read and the write are one transaction because they are one decision: without it a
     * concurrent scan could delete the row between the `SELECT` and the `INSERT`, and the row would
     * be reinserted under an id that no longer belongs to anything.
     *
     * A row whose id is [com.kaislate.veldtplayer.data.library.model.Song.UNSAVED] and whose
     * natural key is new is the genuinely-new case: it falls through with `id = 0` and Room's
     * `AUTOINCREMENT` assigns the next never-yet-used number. See [SongEntity.id] for why "never
     * yet used" rather than "next free" is the property that matters.
     *
     * Per-row rather than a bulk insert, because the carried id differs per row. The loop is inside
     * one transaction, so it is one commit regardless of [rows]' size.
     */
    @Transaction
    suspend fun upsertBySourceKey(rows: List<SongEntity>) {
        rows.forEach { row ->
            val existing = findIdBySourceKey(row.sourceId, row.externalId)
            insertReplacing(if (existing == null) row else row.copy(id = existing))
        }
    }

    /** The surrogate currently standing for this natural key, or null if it is new. */
    @Query("SELECT id FROM songs WHERE sourceId = :sourceId AND externalId = :externalId")
    suspend fun findIdBySourceKey(sourceId: String, externalId: String): Long?

    /**
     * The raw insert. **Production code calls this only via [upsertBySourceKey]** — on its own it
     * is the id-churning write that method exists to prevent, and it is exposed only because the
     * transaction needs it. (`SongDaoTest` also uses it directly, to keep the unique index itself
     * falsifiable; see that test's KDoc.)
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReplacing(row: SongEntity)

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY discNumber, trackNumber")
    suspend fun getSongsByAlbum(album: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album COLLATE NOCASE, discNumber, trackNumber")
    suspend fun getSongsByArtist(artist: String): List<SongEntity>

    @Query(
        "SELECT * FROM songs WHERE title LIKE :pattern OR artist LIKE :pattern " +
            "OR album LIKE :pattern ORDER BY title COLLATE NOCASE"
    )
    suspend fun search(pattern: String): List<SongEntity>

    @Query(
        "SELECT * FROM songs WHERE title LIKE :pattern OR artist LIKE :pattern " +
            "OR album LIKE :pattern ORDER BY title COLLATE NOCASE"
    )
    fun observeSearch(pattern: String): Flow<List<SongEntity>>

    /**
     * `relativeKey` is projected so the diff can see a move; see [IndexEntry]. Scoped to one
     * source: the local scan must never diff against — or delete — another source's rows.
     */
    @Query("SELECT externalId, dateModifiedSec, relativeKey FROM songs WHERE sourceId = :sourceId")
    suspend fun getIndex(sourceId: String): List<IndexEntry>

    /**
     * Delete rows this source no longer enumerates. Keyed on `(sourceId, externalId)` — the row's
     * real identity — and NOT on [SongEntity.id], which is an app-internal surrogate no source can
     * name. There is deliberately no id-keyed delete on this DAO: with two sources sharing one id
     * space, an id-keyed delete driven by one source's scan is a data-loss bug waiting for its
     * second caller.
     */
    @Query("DELETE FROM songs WHERE sourceId = :sourceId AND externalId IN (:externalIds)")
    suspend fun deleteByExternalIds(sourceId: String, externalIds: List<String>)

    @Query("DELETE FROM songs")
    suspend fun clear()
}
