// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity

// v2 adds the playlist tables; v3 adds SongEntity.relativeKey, the rescan-stable playlist key;
// v4 volume-qualifies that key's FORMAT (external_primary:Music/a.mp3), which changes the stored
// values in songs.relativeKey and playlist_entries.sourceKey even though no column changed shape.
// The bump is what discards the old-format rows: left in place they would be unqualified keys that
// collide across storage volumes, which is the defect v4 exists to fix.
//
// The app is pre-release with zero users, so the builder's destructive migration is the correct
// upgrade path; no Migration is written (Global Constraint 7).
//
// This wipes the songs table, which a rescan rebuilds — but it also wipes the playlist tables,
// which nothing can regenerate. Acceptable only pre-release; see DatabaseModule's standing note.
@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class VeldtDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object { const val NAME = "veldt-library.db" }
}
