package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry

/** Result of a scan diff: MediaStore ids partitioned into added / changed / removed. */
data class ScanDiff(val added: List<Long>, val changed: List<Long>, val removed: List<Long>)

/**
 * Pure diff of the current DB index vs. a fresh scan, keyed by MediaStore `_ID`.
 *
 * - `added`   = scanned ids absent from current
 * - `changed` = ids present in both whose `dateModifiedSec` differs
 * - `removed` = current ids absent from scanned
 *
 * Framework-free and side-effect-free, so it is unit-tested on the JVM. Lets a rescan
 * touch only the rows that actually moved instead of a full clear+reinsert.
 */
object ScanDiffer {
    fun diff(current: List<IndexEntry>, scanned: List<IndexEntry>): ScanDiff {
        val currentById = current.associate { it.id to it.dateModifiedSec }
        val scannedById = scanned.associate { it.id to it.dateModifiedSec }
        val added = scanned.filter { it.id !in currentById }.map { it.id }
        val changed = scanned.filter {
            val prev = currentById[it.id]
            prev != null && prev != it.dateModifiedSec
        }.map { it.id }
        val removed = current.filter { it.id !in scannedById }.map { it.id }
        return ScanDiff(added = added, changed = changed, removed = removed)
    }
}
