// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure reorder semantics. These are the directional tests the negative control targets: swapping
 * `add(to, removeAt(from))` for `add(from, removeAt(to))` must turn both of the first two red.
 */
class PlaylistOrderingTest {

    @Test fun `moving an item down shifts the span between it and the target up`() {
        assertEquals(listOf(1, 2, 3, 0, 4), PlaylistOrdering.reorder(listOf(0, 1, 2, 3, 4), 0, 3))
    }

    @Test fun `moving an item up shifts the span between it and the target down`() {
        assertEquals(listOf(0, 3, 1, 2, 4), PlaylistOrdering.reorder(listOf(0, 1, 2, 3, 4), 3, 1))
    }

    @Test fun `moving an item onto itself changes nothing`() {
        assertEquals(listOf(0, 1, 2), PlaylistOrdering.reorder(listOf(0, 1, 2), 1, 1))
    }

    // The brief's version of this asserted `out.indices.toList()` against itself, which is true of
    // any list. Assert the actual permutation, then the structural properties on top of it.
    @Test fun `positions stay gap-free after a move`() {
        val out = PlaylistOrdering.reorder(listOf(0, 1, 2, 3), 3, 0)
        assertEquals(listOf(3, 0, 1, 2), out)
        assertEquals(4, out.size)
        assertEquals(out.size, out.toSet().size)
        // A renumber over this list is exactly 0..n-1, which is what the repository writes back.
        assertEquals(listOf(0, 1, 2, 3), out.indices.toList())
    }

    @Test fun `moving to the last index lands at the end`() {
        assertEquals(listOf(1, 2, 3, 0), PlaylistOrdering.reorder(listOf(0, 1, 2, 3), 0, 3))
    }

    @Test fun `a no-op move returns the input list untouched`() {
        val input = listOf("a", "b", "c")
        assertSame(input, PlaylistOrdering.reorder(input, 2, 2))
    }

    @Test fun `reorder is generic over the element type`() {
        assertEquals(listOf("b", "a", "c"), PlaylistOrdering.reorder(listOf("a", "b", "c"), 1, 0))
    }
}
