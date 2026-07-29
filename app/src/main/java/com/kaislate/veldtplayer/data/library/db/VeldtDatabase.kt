// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity

// v2 adds the playlist tables; v3 adds SongEntity.relativeKey, the rescan-stable playlist key.
// The app is pre-release with zero users, so the builder's destructive migration is the correct
// upgrade path; no Migration is written (Global Constraint 7).
//
// v3 wipes the songs table, which a rescan rebuilds — but it also wipes the playlist tables, which
// nothing can regenerate. That is acceptable only pre-release; see DatabaseModule's standing note.
@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class VeldtDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object { const val NAME = "veldt-library.db" }
}
