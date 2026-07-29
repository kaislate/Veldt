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

    /**
     * One-shot read of a playlist's entries. The repository's write paths (add/remove/move) are
     * read-modify-write and must not subscribe to a Flow to do it, so this exists alongside
     * [observeEntries] rather than replacing it.
     */
    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getEntries(playlistId: Long): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE id = :entryId")
    suspend fun getEntry(entryId: Long): PlaylistEntryEntity?

    /** `-1` for an empty playlist, so the first appended entry lands at position 0. */
    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

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
     * Close the hole left by a deleted entry. There is deliberately no unique index on
     * `(playlistId, position)` — one would break a shift-by-one reorder mid-transaction — so
     * nothing at schema level keeps positions dense. This is one half of that contract; the
     * other half is [replaceEntries] renumbering after a move.
     */
    @Query(
        "UPDATE playlist_entries SET position = position - 1 " +
            "WHERE playlistId = :playlistId AND position > :position"
    )
    suspend fun closePositionGap(playlistId: Long, position: Int)

    /** Delete an entry and re-densify the tail, atomically. */
    @Transaction
    suspend fun deleteEntryAndCompact(entryId: Long): Long? {
        val entry = getEntry(entryId) ?: return null
        deleteEntry(entryId)
        closePositionGap(entry.playlistId, entry.position)
        return entry.playlistId
    }

    /**
     * Write back re-resolved [PlaylistEntryEntity.songId] caches in one transaction, so a
     * resolution sweep is never observed half-applied.
     */
    @Transaction
    suspend fun updateResolvedSongIds(updates: Map<Long, Long?>) {
        updates.forEach { (entryId, songId) -> updateResolvedSongId(entryId, songId) }
    }

    @Query("UPDATE playlist_entries SET songId = :songId WHERE id = :entryId")
    suspend fun updateResolvedSongId(entryId: Long, songId: Long?)

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
