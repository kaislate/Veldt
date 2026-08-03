// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry

/** Result of a scan diff: MediaStore ids partitioned into added / changed / removed. */
data class ScanDiff(val added: List<Long>, val changed: List<Long>, val removed: List<Long>)

/**
 * Pure diff of the current DB index vs. a fresh scan, keyed by MediaStore `_ID`.
 *
 * - `added`   = scanned ids absent from current
 * - `changed` = ids present in both whose `dateModifiedSec` **or [IndexEntry.relativeKey]** differs
 * - `removed` = current ids absent from scanned
 *
 * **Why the key is in the changed clause.** `dateModifiedSec` alone cannot see a file that MOVED:
 * a move keeps the MediaStore `_ID` (the row is updated, not reinserted) and keeps the file's
 * mtime (POSIX `rename(2)` touches the directory's, not the file's). Diffing on `(id, mtime)` only,
 * the row is never re-upserted and its stored `filePath`/`relativeKey` stay stale forever — which
 * then makes `PlaylistRepository.resolve` miss rung 1 and an `.m3u` naming the new path miss every
 * rung. See [IndexEntry] for the full derivation and the one residual it does not cover.
 *
 * Framework-free and side-effect-free, so it is unit-tested on the JVM. Lets a rescan
 * touch only the rows that actually moved instead of a full clear+reinsert.
 */
object ScanDiffer {
    fun diff(current: List<IndexEntry>, scanned: List<IndexEntry>): ScanDiff {
        val currentById = current.associateBy { it.id }
        val scannedById = scanned.associateBy { it.id }
        val added = scanned.filter { it.id !in currentById }.map { it.id }
        val changed = scanned.filter {
            val prev = currentById[it.id]
            prev != null &&
                (prev.dateModifiedSec != it.dateModifiedSec || prev.relativeKey != it.relativeKey)
        }.map { it.id }
        val removed = current.filter { it.id !in scannedById }.map { it.id }
        return ScanDiff(added = added, changed = changed, removed = removed)
    }
}
