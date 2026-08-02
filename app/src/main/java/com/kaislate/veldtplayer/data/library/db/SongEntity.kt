// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
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
 * [relativeKey] is carried because **`(id, dateModifiedSec)` cannot see a file that moved.** A move
 * keeps the MediaStore `_ID` — API 30+ file managers go through `ContentResolver.update` or a
 * FUSE-intercepted `rename`, and both UPDATE the row rather than delete-and-reinsert — and it keeps
 * `dateModifiedSec`, because that is the FILE's mtime and POSIX `rename(2)` touches the DIRECTORY's
 * mtime. (Google adding `GENERATION_MODIFIED` in API 30 is the tell.) So the location is the third
 * thing the diff has to compare, or the stored `filePath`/`relativeKey` go stale permanently and no
 * rescan ever corrects them.
 *
 * It is a projection of an existing column, so it costs no schema change.
 *
 * **Residual, deliberately not covered here:** when [relativeKey] is null — an OEM provider that
 * withholds `VOLUME_NAME`/`RELATIVE_PATH`/`DISPLAY_NAME`, see
 * [com.kaislate.veldtplayer.data.library.LocalSource.composeRelativeKey] — a move is still
 * invisible, exactly as it was before. Adding `filePath` as a second location column would close
 * that, at the cost of re-upserting every row whenever the mount point spelling changes
 * (`/storage/emulated/0` vs `/storage/self/primary`), which `relativeKey` is immune to.
 */
data class IndexEntry(val id: Long, val dateModifiedSec: Long, val relativeKey: String?)
