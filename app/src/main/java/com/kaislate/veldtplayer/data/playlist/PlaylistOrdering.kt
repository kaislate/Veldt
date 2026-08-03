// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist

/**
 * The drag-to-reorder rule, kept pure so it is testable without Room, Android or coroutines.
 *
 * "Move" means *remove then reinsert*, not "swap": dragging track 0 onto slot 3 has to slide
 * tracks 1..3 up by one, which is what a user watching the list expects. A swap would leave the
 * span between the two indices untouched and is the classic wrong implementation here, so both
 * directions are pinned by their own test.
 *
 * The output is always a permutation of the input, so renumbering it `0..n-1` — which is what
 * [PlaylistRepository.move] writes back — can never produce a gap or a duplicate position.
 */
object PlaylistOrdering {

    /**
     * [items] with the element at [from] moved to index [to]. Returns [items] itself when the
     * move is a no-op. Both indices must be valid indices of [items].
     */
    fun <T> reorder(items: List<T>, from: Int, to: Int): List<T> {
        if (from == to) return items
        require(from in items.indices) { "from=$from out of bounds for ${items.size} items" }
        require(to in items.indices) { "to=$to out of bounds for ${items.size} items" }
        val out = items.toMutableList()
        out.add(to, out.removeAt(from))
        return out
    }
}
