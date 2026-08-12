// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The `songs` row.
 *
 * `(sourceId, externalId)` is the **real** identity of a track and is enforced unique here. [id] is
 * the app-internal handle everything else in the app points at — a **Room-assigned surrogate** from
 * v7 onward, no longer the MediaStore `_ID` — and nothing may read meaning out of it.
 *
 * The index is a pair rather than `externalId` alone because source-native ids are only unique
 * *within* a source: a Subsonic track `42` and a MediaStore `_ID` `42` are different songs and must
 * be able to coexist as two rows.
 */
@Entity(
    tableName = "songs",
    indices = [Index(value = ["sourceId", "externalId"], unique = true)],
)
data class SongEntity(
    /**
     * The surrogate. `autoGenerate = true` makes the column `INTEGER PRIMARY KEY AUTOINCREMENT`,
     * and the `AUTOINCREMENT` half is load bearing rather than incidental: without it SQLite issues
     * `max(rowid) + 1`, so **deleting the highest row hands its id to the next track inserted**. A
     * `playlist_entries.songId` — or an art/palette cache keyed on `songId` — that outlived the
     * deletion would then resolve to a *different song* instead of to nothing. Monotonic ids make
     * every stale reference a miss, which is recoverable, rather than a silent mismatch, which is
     * not. `SongDaoTest.a freed surrogate id is never reissued to a later row` pins it.
     *
     * `0` — [com.kaislate.veldtplayer.data.library.model.Song.UNSAVED] — is Room's "not set"
     * signal and is what a source's enumeration emits; any other value is inserted verbatim, which
     * is what lets fixtures seed chosen ids and assert against them literally.
     */
    @PrimaryKey(autoGenerate = true) val id: Long,
    /** See [com.kaislate.veldtplayer.data.library.model.Song.sourceId]. */
    val sourceId: String,
    /** See [com.kaislate.veldtplayer.data.library.model.Song.externalId]. */
    val externalId: String,
    val uri: String,
    val filePath: String?,
    /** See [com.kaislate.veldtplayer.data.library.model.Song.relativeKey]. */
    val relativeKey: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val dateModifiedSec: Long,
    val hasEmbeddedArt: Boolean,
)

/**
 * Lightweight projection for scan-diff (avoids loading full rows).
 *
 * **Keyed on [externalId], never on [SongEntity.id].** The diff compares what a *source* said about
 * its own library against what the DB stored, so the only identity both sides can possibly agree on
 * is the source-native one. [SongEntity.id] is the app-internal handle — a Room-assigned surrogate
 * as of v7 — which a source has never heard of and a fresh scan cannot reproduce; keyed on that, the
 * first scan after the surrogate flip would classify the entire library as `added` and re-upsert and
 * re-tag every file. The projection is scoped to one source by [SongDao.getIndex], because
 * source-native ids are unique only *within* a source.
 *
 * [relativeKey] is carried because **`(externalId, dateModifiedSec)` cannot see a file that moved.**
 * A move keeps the source-native `externalId` — for the local source that is the MediaStore `_ID`,
 * and API 30+ file managers go through `ContentResolver.update` or a FUSE-intercepted `rename`, both
 * of which UPDATE the row rather than delete-and-reinsert — and it keeps `dateModifiedSec`, because
 * that is the FILE's mtime and POSIX `rename(2)` touches the DIRECTORY's mtime. (Google adding
 * `GENERATION_MODIFIED` in API 30 is the tell.) So the location is the third thing the diff has to
 * compare, or the stored `filePath`/`relativeKey` go stale permanently and no rescan ever corrects
 * them.
 *
 * It is a projection of existing columns, so it costs no schema change.
 *
 * **Residual, deliberately not covered here:** when [relativeKey] is null — an OEM provider that
 * withholds `VOLUME_NAME`/`RELATIVE_PATH`/`DISPLAY_NAME`, see
 * [com.kaislate.veldtplayer.data.library.LocalSource.composeRelativeKey] — a move is still
 * invisible, exactly as it was before. Adding `filePath` as a second location column would close
 * that, at the cost of re-upserting every row whenever the mount point spelling changes
 * (`/storage/emulated/0` vs `/storage/self/primary`), which `relativeKey` is immune to.
 */
data class IndexEntry(val externalId: String, val dateModifiedSec: Long, val relativeKey: String?)
