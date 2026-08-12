// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every fixture id here is a **non-numeric string** (`"ms-9001"`, never `"1"` and never `1L`).
 * That is Global Constraint 14 turned into a compiler check: this diff used to be keyed on the
 * MediaStore `_ID`, and a regression back to an id-shaped key cannot even typecheck against
 * `"ms-9001"`. A fixture of `"1"` would have let one through.
 */
class ScanDifferTest {

    /**
     * Distinct per-entry keys by default, so the cases below keep testing the `mtime` axis alone
     * rather than accidentally flagging every row `changed` on a shared key.
     */
    private fun e(
        externalId: String,
        modified: Long,
        key: String? = "external_primary:Music/$externalId.mp3",
    ) = IndexEntry(externalId = externalId, dateModifiedSec = modified, relativeKey = key)

    @Test fun addedChangedRemoved_areClassifiedByExternalId() {
        val current = listOf(e("ms-1", 100), e("ms-2", 100), e("ms-3", 100))
        val scanned = listOf(e("ms-2", 100), e("ms-3", 200), e("ms-4", 100))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf("ms-4"), diff.added)     // in scanned, not current
        assertEquals(listOf("ms-3"), diff.changed)   // in both, date differs
        assertEquals(listOf("ms-1"), diff.removed)   // in current, not scanned
    }

    @Test fun identical_isNoOp() {
        val same = listOf(e("ms-1", 100), e("ms-2", 200))
        val diff = ScanDiffer.diff(same, same)
        assertEquals(emptyList<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.changed)
        assertEquals(emptyList<String>(), diff.removed)
    }

    /**
     * **THE anti-churn property**, stated over two *equal-but-not-identical* lists. `identical_isNoOp`
     * above hands the very same `List` object to both sides, so it would also pass for a diff keyed
     * on object identity or on a per-call surrogate; this one cannot. Keyed on anything a scan does
     * not preserve — a Room surrogate id, an object identity — this diff re-upserts the entire
     * library, and re-reads every file's tags, on every single run.
     */
    @Test fun `an unchanged scan produces an empty diff`() {
        val rows = listOf(
            IndexEntry("ms-9001", 10L, "external_primary:Music/a.mp3"),
            IndexEntry("ms-9002", 20L, "external_primary:Music/b.mp3"),
        )
        assertEquals(
            ScanDiff(added = emptyList(), changed = emptyList(), removed = emptyList()),
            ScanDiffer.diff(current = rows, scanned = rows.map { it.copy() }),
        )
    }

    /**
     * Counts collapse; names do not. `added = 1, changed = 1, removed = 1` is satisfied by an
     * implementation that permutes the three buckets, and a permuted diff deletes the row it should
     * have upserted. Each bucket names WHICH externalId, and the three ids are mutually distinct so
     * a swap has nowhere to hide.
     */
    @Test fun `added, changed and removed name WHICH externalIds, not how many`() {
        val current = listOf(IndexEntry("ms-1", 10L, null), IndexEntry("ms-2", 10L, null))
        val scanned = listOf(IndexEntry("ms-2", 99L, null), IndexEntry("ms-3", 10L, null))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf("ms-3"), diff.added)
        assertEquals(listOf("ms-2"), diff.changed)
        assertEquals(listOf("ms-1"), diff.removed)
    }

    @Test fun emptyCurrent_allAdded() {
        val diff = ScanDiffer.diff(emptyList(), listOf(e("ms-1", 1), e("ms-2", 2)))
        assertEquals(listOf("ms-1", "ms-2"), diff.added.sorted())
    }

    @Test fun emptyScan_allRemoved() {
        val diff = ScanDiffer.diff(listOf(e("ms-1", 1), e("ms-2", 2)), emptyList())
        assertEquals(listOf("ms-1", "ms-2"), diff.removed.sorted())
        assertEquals(emptyList<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.changed)
    }

    // ---- the moved file ----------------------------------------------------------------------

    /**
     * **The defect this diff was blind to.** A file dragged into another folder by a file manager
     * keeps its source-native `externalId` — for the local source that is the MediaStore `_ID`, and
     * API 30+ managers go through `ContentResolver.update` or a FUSE-intercepted `rename`, both of
     * which UPDATE the row rather than delete-and-reinsert — AND keeps `dateModifiedSec`, because
     * that is the FILE's mtime and POSIX `rename(2)` touches the DIRECTORY's mtime, not the file's.
     *
     * So both of the fields the diff used to compare are byte-identical across a move, and the row
     * was never re-upserted: its stored `filePath`/`relativeKey` went stale permanently and no
     * rescan corrected them.
     *
     * Note what is asserted: `changed` holds exactly `["ms-1"]` and `added`/`removed` are empty. A
     * move is not a delete-plus-insert — asserting only `changed.isNotEmpty()` would also pass for
     * an implementation that dropped the row and re-added it under a new identity, losing every
     * playlist entry keyed on the old one.
     */
    @Test fun aMoveIsChanged_sameExternalIdAndSameMtime_differentRelativeKey() {
        val current = listOf(e("ms-1", 100, "external_primary:Music/a.mp3"))
        val scanned = listOf(e("ms-1", 100, "external_primary:Podcasts/a.mp3"))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf("ms-1"), diff.changed)
        assertEquals(emptyList<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.removed)
    }

    /**
     * The key must be compared whole, not by its filename part: a file moved between folders keeps
     * its `DISPLAY_NAME`, and that is the commonest move there is.
     */
    @Test fun aMoveBetweenFoldersKeepingTheFilenameIsStillChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e("ms-1", 100, "external_primary:Music/Beck/Lost Cause.mp3")),
            scanned = listOf(e("ms-1", 100, "external_primary:Music/Sea Change/Lost Cause.mp3")),
        )
        assertEquals(listOf("ms-1"), diff.changed)
    }

    /** A copy to another volume at the same relative path is a genuinely different file. */
    @Test fun aMoveToAnotherVolumeIsChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e("ms-1", 100, "external_primary:Music/a.mp3")),
            scanned = listOf(e("ms-1", 100, "1234-5678:Music/a.mp3")),
        )
        assertEquals(listOf("ms-1"), diff.changed)
    }

    /**
     * An unmoved file must NOT be flagged. Without this, "the move test passes" would be satisfied
     * by an implementation that marks every row changed — a full re-upsert and a full tag re-read
     * of the library on every scan, which is exactly what the diff exists to avoid.
     */
    @Test fun anUnmovedFileWithTheSameKeyAndMtimeIsNotChanged() {
        val current = listOf(e("ms-1", 100, "external_primary:Music/a.mp3"), e("ms-2", 5, "x:y/b.mp3"))
        val scanned = listOf(e("ms-1", 100, "external_primary:Music/a.mp3"), e("ms-2", 5, "x:y/b.mp3"))
        assertEquals(emptyList<String>(), ScanDiffer.diff(current, scanned).changed)
    }

    /**
     * An edited file that did NOT move is still changed — the mtime half of the clause has to keep
     * working, or "compare the key" would silently become "compare only the key".
     */
    @Test fun anEditedFileThatDidNotMoveIsStillChanged() {
        val diff = ScanDiffer.diff(
            current = listOf(e("ms-1", 100, "external_primary:Music/a.mp3")),
            scanned = listOf(e("ms-1", 999, "external_primary:Music/a.mp3")),
        )
        assertEquals(listOf("ms-1"), diff.changed)
    }

    /**
     * Both null — a provider that withholds the location columns on both sides — is NOT a move.
     * `null != null` would be false in Kotlin anyway, but an implementation reaching for
     * `orEmpty()` on one side and not the other would flag every such row forever.
     */
    @Test fun bothKeysNullIsNotChanged() {
        val diff = ScanDiffer.diff(listOf(e("ms-1", 100, null)), listOf(e("ms-1", 100, null)))
        assertEquals(emptyList<String>(), diff.changed)
    }

    /** A key appearing where there was none (or vanishing) is a real change to the stored row. */
    @Test fun aKeyAppearingOrVanishingIsChanged() {
        assertEquals(
            listOf("ms-1"),
            ScanDiffer.diff(listOf(e("ms-1", 100, null)), listOf(e("ms-1", 100, "v:d/a.mp3"))).changed,
        )
        assertEquals(
            listOf("ms-1"),
            ScanDiffer.diff(listOf(e("ms-1", 100, "v:d/a.mp3")), listOf(e("ms-1", 100, null))).changed,
        )
    }

    /**
     * Two files SWAPPING places: both externalIds move, neither key leaves the library. Each must be
     * changed, and neither added nor removed — an implementation that diffed on the relativeKey
     * instead of the externalId (rather than as well as) would call this a no-op.
     */
    @Test fun twoFilesSwappingPathsAreBothChanged() {
        val a = "external_primary:Music/a.mp3"
        val b = "external_primary:Music/b.mp3"
        val diff = ScanDiffer.diff(
            current = listOf(e("ms-1", 100, a), e("ms-2", 100, b)),
            scanned = listOf(e("ms-1", 100, b), e("ms-2", 100, a)),
        )
        assertEquals(listOf("ms-1", "ms-2"), diff.changed.sorted())
        assertEquals(emptyList<String>(), diff.added)
        assertEquals(emptyList<String>(), diff.removed)
    }

    /**
     * The externalId is compared as text, not as a number. Two source-native ids that a numeric
     * reading would collapse — `"007"` and `"7"` — are two different tracks on any source that
     * hands out zero-padded or otherwise non-canonical ids (Subsonic and Jellyfin both hand out
     * opaque strings). Asserted as the non-collapse of that named pair: if the diff ever parsed the
     * id, `"007"` in current and `"7"` in scanned would look like one unchanged row and this
     * assertion's failure message would show the empty buckets.
     */
    @Test fun `externalIds that differ only outside a numeric reading are two distinct tracks`() {
        val diff = ScanDiffer.diff(
            current = listOf(e("007", 100, "external_primary:Music/a.mp3")),
            scanned = listOf(e("7", 100, "external_primary:Music/b.mp3")),
        )
        assertEquals(listOf("7"), diff.added)
        assertEquals(listOf("007"), diff.removed)
        assertEquals(emptyList<String>(), diff.changed)
    }
}
