// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One track in a playlist.
 *
 * **Identity is `(sourceId, sourceKey)`, not [songId].** `sourceId` is the owning
 * [com.kaislate.veldtplayer.data.library.LibrarySource.id]; `sourceKey` is that source's stable
 * key for the track. [songId] is only a *cache* of the last successful resolution against the
 * `songs` table, and is deliberately nullable: an entry whose song is not currently in the
 * library is a valid, first-class state (the file was moved, the volume is unmounted, the source
 * has not been scanned yet), never an error and never a reason to drop the row.
 *
 * [sourceTitle]/[sourceArtist]/[sourceAlbum] are the denormalised display strings captured when
 * the entry was added, so an unresolved entry still renders as something meaningful.
 *
 * Indices: `playlistId` for the per-playlist read, `(sourceId, sourceKey)` for the
 * re-resolution sweep that re-links entries after a library scan.
 */
@Entity(
    tableName = "playlist_entries",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("playlistId"),
        Index("sourceId", "sourceKey"),
    ],
)
data class PlaylistEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val playlistId: Long,
    val position: Int,
    val sourceId: String,
    val sourceKey: String,
    /** Resolved `songs.id`, or null when the track is not currently in the library. */
    val songId: Long?,
    val sourceTitle: String,
    val sourceArtist: String,
    val sourceAlbum: String,
)
