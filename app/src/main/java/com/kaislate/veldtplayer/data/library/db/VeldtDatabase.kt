// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity

// v2 adds the playlist tables; v3 adds SongEntity.relativeKey, the rescan-stable playlist key;
// v4 volume-qualifies that key's FORMAT (external_primary:Music/a.mp3); v5 stops trimming
// whitespace out of the directory and filename parts, so " a.mp3" no longer keys as "a.mp3".
//
// v4 and v5 change stored VALUES, not column shapes. The bump is what discards the old-format
// rows, and it is required rather than cosmetic: nothing rewrites an existing
// playlist_entries.sourceKey, and songs.relativeKey goes stale for every row the scan diff does
// not touch (the diff keys on dateModifiedSec, which a format change does not move). Without a
// bump the defect would survive only on upgraded devices — worse to diagnose than one that is
// uniform.
//
// The app is pre-release with zero users, so the builder's destructive migration is the correct
// upgrade path; no Migration is written (Global Constraint 7).
//
// This wipes the songs table, which a rescan rebuilds — but it also wipes the playlist tables,
// which nothing can regenerate. Acceptable only pre-release; see DatabaseModule's standing note.
@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class VeldtDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object { const val NAME = "veldt-library.db" }
}
