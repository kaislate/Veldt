// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.account.db.AccountEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntity
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity

// v2 adds the playlist tables; v3 adds SongEntity.relativeKey, the rescan-stable playlist key;
// v4 volume-qualifies that key's FORMAT (external_primary:Music/a.mp3); v5 stops trimming
// whitespace out of the directory and filename parts, so " a.mp3" no longer keys as "a.mp3".
//
// v4 and v5 change stored VALUES, not column shapes. The bump is what discards the old-format
// rows, and it was required rather than cosmetic at the time: nothing rewrote an existing
// playlist_entries.sourceKey, and songs.relativeKey went stale for every row the scan diff did
// not touch (the diff keyed on dateModifiedSec alone, which a format change does not move).
// Without a bump the defect would have survived only on upgraded devices — worse to diagnose than
// one that is uniform.
//
// Both halves of that are now self-healing, which is why v5 is the last bump this reason produces:
// ScanDiffer also compares songs.relativeKey, so a format change re-upserts every row; and
// PlaylistRepository.resolve rewrites a stale playlist_entries.sourceKey whenever the cached songId
// still finds the track. A future key-format change is a behaviour change to review on its merits,
// not an automatic version bump.
//
// v6 adds the source dimension (`sourceId`, `externalId`, unique index) while the PK is still the
// MediaStore `_ID`; v7 makes the PK a surrogate. Splitting the dimension from the PK flip is what
// keeps each commit green — the column shape changes here, the meaning of `id` changes there.
//
// v7 rewrites `songs.id` as `INTEGER PRIMARY KEY AUTOINCREMENT`. The bump is required rather than
// cosmetic: the column's DDL changes (a plain PK cannot start auto-generating), and every stored id
// changes meaning from "the MediaStore _ID" to "a Room surrogate". Carried across, the old ids would
// be indistinguishable from surrogates while colliding with the ones AUTOINCREMENT is about to
// issue, and `playlist_entries.songId` would keep pointing at the old meaning. Dropping the tables
// makes the first rescan the single moment the id space changes hands.
//
// The app is pre-release with zero users, so the builder's destructive migration is the correct
// upgrade path; no Migration is written (Global Constraint 7).
//
// This wipes the songs table, which a rescan rebuilds — but it also wipes the playlist tables,
// which nothing can regenerate. Acceptable only pre-release; see DatabaseModule's standing note.
//
// v8 adds the `accounts` table (N1). The bump is required rather than cosmetic — a new entity
// changes the schema Room validates at open — and the destructive path still applies: the app
// is pre-release with zero users (Global Constraint 10). Note what a destructive upgrade means
// here that it did not before: it drops CONFIGURED ACCOUNTS, which no rescan regenerates. It
// does not drop the sealed secret FILES, which are outside the database; AccountRepository's
// delete is the only thing that removes those, so a destructive upgrade orphans them. That is
// acceptable pre-release and is the reason the next schema change after real users exist must
// be a real Migration.
@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class, AccountEntity::class],
    version = 8,
    exportSchema = false,
)
abstract class VeldtDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun accountDao(): AccountDao

    companion object { const val NAME = "veldt-library.db" }
}
