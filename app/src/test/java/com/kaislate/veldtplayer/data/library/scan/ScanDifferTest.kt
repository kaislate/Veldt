// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDifferTest {

    /**
     * Distinct per-id keys by default, so the pre-existing cases below keep testing the `mtime`
     * axis alone rather than accidentally flagging every row `changed` on a shared key.
     */
    private fun e(id: Long, modified: Long, key: String? = "external_primary:Music/$id.mp3") =
        IndexEntry(id = id, dateModifiedSec = modified, relativeKey = key)

    @Test fun addedChangedRemoved_areClassifiedById() {
        val current = listOf(e(1, 100), e(2, 100), e(3, 100))
        val scanned = listOf(e(2, 100), e(3, 200), e(4, 100))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf(4L), diff.added)     // in scanned, not current
        assertEquals(listOf(3L), diff.changed)   // in both, date differs
        assertEquals(listOf(1L), diff.removed)   // in current, not scanned
    }

    @Test fun identical_isNoOp() {
        val same = listOf(e(1, 100), e(2, 200))
        val diff = ScanDiffer.diff(same, same)
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.changed)
        assertEquals(emptyList<Long>(), diff.removed)
    }

    @Test fun emptyCurrent_allAdded() {
        val diff = ScanDiffer.diff(emptyList(), listOf(e(1, 1), e(2, 2)))
        assertEquals(listOf(1L, 2L), diff.added.sorted())
    }

    @Test fun emptyScan_allRemoved() {
        val diff = ScanDiffer.diff(listOf(e(1, 1), e(2, 2)), emptyList())
        assertEquals(listOf(1L, 2L), diff.removed.sorted())
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.changed)
    }

    // ---- the moved file ----------------------------------------------------------------------

    /**
     * **The defect this diff was blind to.** A file dragged into another folder by a file manager
     * keeps its MediaStore `_ID` (API 30+ managers go through `ContentResolver.update` or a
     * FUSE-intercepted `rename`, both of which UPDATE the row rather than delete-and-reinsert) AND
     * keeps `dateModifiedSec` — that is the FILE's mtime, and POSIX `rename(2)` touches the
     * DIRECTORY's mtime, not the file's.
     *
     * So both of the fields the diff used to compare are byte-identical across a move, and the row
     * was never re-upserted: its stored `filePath`/`relativeKey` went stale permanently and no
     * rescan corrected them.
     *
     * Note what is asserted: `changed` holds exactly `[1]` and `added`/`removed` are empty. A move
     * is not a delete-plus-insert — asserting only `changed.isNotEmpty()` would also pass for an
     * implementation that dropped the row and re-added it under a new id, losing every playlist
     * entry keyed on the old one.
     */
    @Test fun aMoveIsChanged_sameIdAndSameMtime_differentRelativeKey() {
        val current = listOf(e(1, 100, "external_primary:Music/a.mp3"))
        val scanned = listOf(e(1, 100, "external_primary:Podcasts/a.mp3"))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf(1L), diff.changed)
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.removed)
    }

    /**
     * The key must be compared whole, not by its filename part: a file moved between folders keeps
     * its `DISPLAY_NAME`, and that is the commonest move there is.
     */
    @Test fun aMoveBetweenFoldersKeepingTheFilenameIsStillChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e(1, 100, "external_primary:Music/Beck/Lost Cause.mp3")),
            scanned = listOf(e(1, 100, "external_primary:Music/Sea Change/Lost Cause.mp3")),
        )
        assertEquals(listOf(1L), diff.changed)
    }

    /** A copy to another volume at the same relative path is a genuinely different file. */
    @Test fun aMoveToAnotherVolumeIsChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e(1, 100, "external_primary:Music/a.mp3")),
            scanned = listOf(e(1, 100, "1234-5678:Music/a.mp3")),
        )
        assertEquals(listOf(1L), diff.changed)
    }

    /**
     * An unmoved file must NOT be flagged. Without this, "the move test passes" would be satisfied
     * by an implementation that marks every row changed — a full re-upsert and a full tag re-read
     * of the library on every scan, which is exactly what the diff exists to avoid.
     */
    @Test fun anUnmovedFileWithTheSameKeyAndMtimeIsNotChanged() {
        val current = listOf(e(1, 100, "external_primary:Music/a.mp3"), e(2, 5, "x:y/b.mp3"))
        val scanned = listOf(e(1, 100, "external_primary:Music/a.mp3"), e(2, 5, "x:y/b.mp3"))
        assertEquals(emptyList<Long>(), ScanDiffer.diff(current, scanned).changed)
    }

    /**
     * An edited file that did NOT move is still changed — the mtime half of the clause has to keep
     * working, or "compare the key" would silently become "compare only the key".
     */
    @Test fun anEditedFileThatDidNotMoveIsStillChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e(1, 100, "external_primary:Music/a.mp3")),
            scanned = listOf(e(1, 999, "external_primary:Music/a.mp3")),
        )
        assertEquals(listOf(1L), diff.changed)
    }

    /**
     * Both null — a provider that withholds the location columns on both sides — is NOT a move.
     * `null != null` would be false in Kotlin anyway, but an implementation reaching for
     * `orEmpty()` on one side and not the other would flag every such row forever.
     */
    @Test fun bothKeysNullIsNotChanged() {
        val diff = ScanDiffer.diff(listOf(e(1, 100, null)), listOf(e(1, 100, null)))
        assertEquals(emptyList<Long>(), diff.changed)
    }

    /** A key appearing where there was none (or vanishing) is a real change to the stored row. */
    @Test fun aKeyAppearingOrVanishingIsChanged() {
        assertEquals(
            listOf(1L),
            ScanDiffer.diff(listOf(e(1, 100, null)), listOf(e(1, 100, "v:d/a.mp3"))).changed,
        )
        assertEquals(
            listOf(1L),
            ScanDiffer.diff(listOf(e(1, 100, "v:d/a.mp3")), listOf(e(1, 100, null))).changed,
        )
    }

    /**
     * Two files SWAPPING places: both ids move, neither key leaves the library. Each must be
     * changed, and neither added nor removed — an implementation that diffed on the key instead of
     * the id (rather than as well as) would call this a no-op.
     */
    @Test fun twoFilesSwappingPathsAreBothChanged() {
        val a = "external_primary:Music/a.mp3"
        val b = "external_primary:Music/b.mp3"
        val diff = ScanDiffer.diff(
            current = listOf(e(1, 100, a), e(2, 100, b)),
            scanned = listOf(e(1, 100, b), e(2, 100, a)),
        )
        assertEquals(listOf(1L, 2L), diff.changed.sorted())
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.removed)
    }
}
