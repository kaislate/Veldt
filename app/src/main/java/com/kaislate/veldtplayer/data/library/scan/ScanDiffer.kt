// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry

/**
 * Result of a scan diff: source-native [IndexEntry.externalId]s partitioned into added / changed /
 * removed. Strings, not `Long`s — a source-native id is whatever its source says it is (a MediaStore
 * `_ID` rendered as text here, a GUID on a server source), and it is meaningful only within the one
 * source the diff was scoped to.
 */
data class ScanDiff(val added: List<String>, val changed: List<String>, val removed: List<String>)

/**
 * Pure diff of the current DB index vs. a fresh scan, keyed by the source-native
 * [IndexEntry.externalId].
 *
 * - `added`   = scanned externalIds absent from current
 * - `changed` = externalIds present in both whose `dateModifiedSec` **or [IndexEntry.relativeKey]**
 *   differs
 * - `removed` = current externalIds absent from scanned
 *
 * **Both lists must come from ONE source.** `externalId` is unique only within a source, so a caller
 * that mixed two sources' entries would silently merge a Subsonic track `42` with a MediaStore `_ID`
 * `42`. `SongDao.getIndex(sourceId)` is scoped for exactly this reason and the caller
 * ([LibraryScanWorker]) passes its own `LibrarySource.id` to both that read and the matching delete.
 *
 * **Why the externalId and not `Song.id`.** `Song.id` is the app-internal handle — a Room-assigned
 * surrogate from schema v7 — and a scan cannot reproduce it: a source enumerates its own library and
 * has never heard of our primary keys. Keyed on it, every entry would be `added` on the first scan
 * after the flip and the whole library would be re-upserted and re-tagged. The externalId is the one
 * identity both sides can state.
 *
 * **Why the key is in the changed clause.** `dateModifiedSec` alone cannot see a file that MOVED:
 * a move keeps the source-native id (the row is updated, not reinserted) and keeps the file's mtime
 * (POSIX `rename(2)` touches the directory's, not the file's). Diffing on `(externalId, mtime)`
 * only, the row is never re-upserted and its stored `filePath`/`relativeKey` stay stale forever —
 * which then makes `PlaylistRepository.resolve` miss rung 1 and an `.m3u` naming the new path miss
 * every rung. See [IndexEntry] for the full derivation and the one residual it does not cover.
 *
 * Framework-free and side-effect-free, so it is unit-tested on the JVM. Lets a rescan
 * touch only the rows that actually moved instead of a full clear+reinsert.
 */
object ScanDiffer {
    fun diff(current: List<IndexEntry>, scanned: List<IndexEntry>): ScanDiff {
        val currentByExternalId = current.associateBy { it.externalId }
        val scannedByExternalId = scanned.associateBy { it.externalId }
        val added = scanned.filter { it.externalId !in currentByExternalId }.map { it.externalId }
        val changed = scanned.filter {
            val prev = currentByExternalId[it.externalId]
            prev != null &&
                (prev.dateModifiedSec != it.dateModifiedSec || prev.relativeKey != it.relativeKey)
        }.map { it.externalId }
        val removed = current.filter { it.externalId !in scannedByExternalId }.map { it.externalId }
        return ScanDiff(added = added, changed = changed, removed = removed)
    }
}
