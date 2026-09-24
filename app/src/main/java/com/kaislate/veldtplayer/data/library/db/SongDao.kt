// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.kaislate.veldtplayer.data.library.sync.RemoteDiff
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

    /**
     * [deleteByExternalIds], chunked at 500 — SQLite's per-statement bound-variable limit on API
     * 29 (this app's minSdk) is 999, and a delete driven by a full-catalog resync (N2 Task 3) can
     * legitimately name thousands of ids in one call. 500 leaves headroom for the query's other
     * bound parameter ([sourceId]) and is a round number well under the ceiling, not a value
     * chosen to sit exactly at it.
     *
     * `@Transaction` so a multi-chunk delete commits atomically — a caller that reads the source
     * back between chunks must never see a partially-applied delete.
     */
    @Transaction
    suspend fun deleteAllByExternalIds(sourceId: String, externalIds: List<String>) {
        externalIds.chunked(500).forEach { chunk -> deleteByExternalIds(sourceId, chunk) }
    }

    /**
     * N2 Task 3: make [sourceId]'s whole contribution exactly [fetched], in one transaction.
     *
     * [RemoteDiff.plan] decides what changed against the rows already stored for [sourceId]; the
     * upsert and the delete are then each skipped when there is nothing for them to do — not an
     * optimisation for its own sake, but the reason an unchanged resync causes
     * [observeAllSongs]'s Flow not to emit again at all (spec §5.4, "don't redraw an unchanged
     * library"): an empty `IN ()` delete matches no rows and fires no invalidation, but skipping
     * the call entirely is what keeps that true regardless of how the query compiles, and avoids
     * the pointless round trip either way.
     */
    @Transaction
    suspend fun replaceSource(sourceId: String, fetched: List<SongEntity>): RemoteDiff.Plan {
        val existing = getBySource(sourceId)
        val plan = RemoteDiff.plan(existing, fetched)
        if (plan.upserts.isNotEmpty()) upsertBySourceKey(plan.upserts)
        if (plan.removedExternalIds.isNotEmpty()) deleteAllByExternalIds(sourceId, plan.removedExternalIds)
        return plan
    }

    /** Whether an `accounts` row named [sourceId] exists — the `accounts` table lives in the same
     *  [VeldtDatabase], so this is a plain cross-table `@Query`, not a schema change. Exists only
     *  to back [replaceSourceIfPresent]'s in-transaction check. */
    @Query("SELECT COUNT(*) FROM accounts WHERE sourceId = :sourceId")
    suspend fun accountRowCount(sourceId: String): Int

    /**
     * As [replaceSource], but refuses to write anything for an account that is being (or has
     * been) deleted — returns null instead.
     *
     * **Why this exists (fix round 1, Important finding):** `WorkManager.cancelUniqueWork` is
     * cooperative — it does not wait for a `doWork()` already past `fetchCatalog` to stop. Without
     * this check, that in-flight worker's [replaceSource] call could commit AFTER
     * `AccountsViewModel.delete`'s `deleteBySource` purge, re-inserting rows for a `sourceId`
     * whose account no longer exists and that will never sync again — a permanent orphan.
     * Checking [accountRowCount] INSIDE the same `@Transaction` as the write closes the race
     * structurally: SQLite serializes writers, so this call either sees the account row and
     * commits before a concurrent account-row delete, or does not see it and writes nothing —
     * there is no third interleaving. The delete order (cancel the sync → delete the account row
     * → purge songs and status, see `AccountsViewModel.delete`) is what makes "writes nothing" the
     * CORRECT outcome in the second case: the later `deleteBySource` purge still removes any rows
     * a transaction that beat this check managed to commit.
     */
    @Transaction
    suspend fun replaceSourceIfPresent(sourceId: String, fetched: List<SongEntity>): RemoteDiff.Plan? {
        if (accountRowCount(sourceId) <= 0) return null
        return replaceSource(sourceId, fetched)
    }

    /**
     * One source's whole contribution to the library (N2 Task 2) — what
     * [com.kaislate.veldtplayer.data.library.SubsonicSource.listSongs] returns, and Task 6's cover
     * art path over the same account. Unordered here; a caller that needs an order imposes one, the
     * same convention [getIndex] follows.
     */
    @Query("SELECT * FROM songs WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): List<SongEntity>

    /**
     * Drop one source's whole contribution — Task 3's sync worker uses this for an account that was
     * removed, or ahead of a full resync it does not want to diff against. Scoped to [sourceId] for
     * the same reason [deleteByExternalIds] is: there is no id-keyed delete on this DAO, because two
     * sources share one surrogate id space and an unscoped delete driven by one source's logic is a
     * data-loss bug waiting for its second caller.
     */
    @Query("DELETE FROM songs WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("DELETE FROM songs")
    suspend fun clear()
}
