// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    /** Ordering is the playlist's own sequence, so [PlaylistEntryEntity.position] governs. */
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    fun observeEntries(playlistId: Long): Flow<List<PlaylistEntryEntity>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    /** Entries cascade away with the playlist (see [PlaylistEntryEntity]'s foreign key). */
    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteAllEntries(playlistId: Long)

    /**
     * Swap a playlist's whole sequence in one transaction, so a reorder can never be observed
     * half-written. Entry ids are not preserved — identity is `(sourceId, sourceKey)`.
     */
    @Transaction
    suspend fun replaceEntries(playlistId: Long, entries: List<PlaylistEntryEntity>) {
        deleteAllEntries(playlistId)
        insertEntries(entries)
    }
}
