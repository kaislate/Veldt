// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SongEntity>)

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
