package com.kaislate.veldtplayer.data.library.scan

import com.kaislate.veldtplayer.data.library.db.IndexEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDifferTest {
    @Test fun addedChangedRemoved_areClassifiedById() {
        val current = listOf(IndexEntry(1, 100), IndexEntry(2, 100), IndexEntry(3, 100))
        val scanned = listOf(IndexEntry(2, 100), IndexEntry(3, 200), IndexEntry(4, 100))
        val diff = ScanDiffer.diff(current, scanned)
        assertEquals(listOf(4L), diff.added)     // in scanned, not current
        assertEquals(listOf(3L), diff.changed)   // in both, date differs
        assertEquals(listOf(1L), diff.removed)   // in current, not scanned
    }

    @Test fun identical_isNoOp() {
        val same = listOf(IndexEntry(1, 100), IndexEntry(2, 200))
        val diff = ScanDiffer.diff(same, same)
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.changed)
        assertEquals(emptyList<Long>(), diff.removed)
    }

    @Test fun emptyCurrent_allAdded() {
        val diff = ScanDiffer.diff(emptyList(), listOf(IndexEntry(1, 1), IndexEntry(2, 2)))
        assertEquals(listOf(1L, 2L), diff.added.sorted())
    }

    @Test fun emptyScan_allRemoved() {
        val diff = ScanDiffer.diff(listOf(IndexEntry(1, 1), IndexEntry(2, 2)), emptyList())
        assertEquals(listOf(1L, 2L), diff.removed.sorted())
        assertEquals(emptyList<Long>(), diff.added)
        assertEquals(emptyList<Long>(), diff.changed)
    }
}
