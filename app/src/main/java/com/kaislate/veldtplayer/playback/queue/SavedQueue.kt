// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.SongArt

/**
 * One queue entry as it is written to disk (spec §3): the session mediaId, the LOGICAL playback
 * uri, and enough display metadata to rebuild the `MediaItem` without the database.
 *
 * [uri] is whatever `SessionMediaItem` put on the item — `content://` for a local track,
 * `veldt://track/…` for a server one — so nothing written here is ever a resolved stream url and
 * no credential can reach the file (the same guarantee `VeldtUri` gives the session itself).
 *
 * [art] is the decoded `VeldtArtUri` rather than its string, so the browse tree can hand it to the
 * art provider without parsing an android `Uri` — which keeps everything that reads a saved queue
 * testable on the plain JVM.
 */
data class SavedItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val art: SongArt?,
)

/**
 * The whole saved playback state (spec §3): the queue, where in it, and the modes that shape what
 * "next" means. [repeatMode] is the Media3 `Player.REPEAT_MODE_*` int, stored as-is.
 *
 * [shuffleOrder] is the player's shuffled traversal, `null` when the player's own order could not
 * be read or does not fit the queue. It is kept even while shuffle is OFF, because Media3 keeps it
 * too: turning shuffle back on after a restore should continue the same shuffle, not deal a new
 * one.
 */
data class SavedQueue(
    val items: List<SavedItem>,
    val index: Int,
    val positionMs: Long,
    val shuffleEnabled: Boolean,
    val shuffleOrder: List<Int>?,
    val repeatMode: Int,
) {
    val current: SavedItem? get() = items.getOrNull(index)
}

/**
 * The pure rules for turning a live queue into a savable one and a saved one into a restorable
 * one. Everything that decides WHICH items and WHICH index lives here, so none of it needs a
 * player, a file or a database to be tested.
 */
object SavedQueues {

    /** Spec §3: the saved queue's cap. */
    const val CAP = 2_000

    /**
     * [queue] cut to at most [cap] items around its current index (spec §3). The index, and the
     * shuffle order, are rebased onto the window; shuffle entries that fall outside it are
     * dropped and the rest keep their relative order, so the part of the shuffle that survives is
     * still the same shuffle.
     *
     * A quarter of the window sits BEHIND the current item: what is still to come is what a
     * resumed session plays, so most of it looks forward, but a "previous" press after a restore
     * should still have somewhere to go. Near either end of the queue the window slides so that it
     * is always full.
     */
    fun capped(queue: SavedQueue, cap: Int = CAP): SavedQueue {
        val size = queue.items.size
        if (size <= cap) return queue
        val behind = cap / 4
        val end = minOf(size, maxOf(queue.index - behind, 0) + cap)
        val start = end - cap
        return queue.copy(
            items = queue.items.subList(start, end).toList(),
            index = queue.index - start,
            shuffleOrder = queue.shuffleOrder
                ?.filter { it in start until end }
                ?.map { it - start },
        )
    }

    /**
     * [queue] with every item [resolve] returns null for dropped, and every survivor replaced by
     * what [resolve] returned (a restore refreshes stale metadata this way). Null when nothing
     * survives — there is then nothing to restore, and resumption opts out.
     *
     * **Where the index goes.** If the current item survives it stays current, at its saved
     * position. If it was dropped, the item that now occupies its place — the next survivor —
     * becomes current, from the start: the saved position belonged to a track that no longer
     * exists. If nothing after it survived, the last survivor takes over, also from the start.
     */
    fun pruned(queue: SavedQueue, resolve: (SavedItem) -> SavedItem?): SavedQueue? {
        val keptOld = ArrayList<Int>(queue.items.size)
        val kept = ArrayList<SavedItem>(queue.items.size)
        queue.items.forEachIndexed { i, item ->
            resolve(item)?.let {
                keptOld += i
                kept += it
            }
        }
        if (kept.isEmpty()) return null
        val oldIndex = queue.index.coerceIn(0, queue.items.lastIndex)
        val same = keptOld.indexOf(oldIndex)
        val (index, position) = when {
            same >= 0 -> same to queue.positionMs.coerceAtLeast(0L)
            else -> {
                val next = keptOld.indexOfFirst { it > oldIndex }
                (if (next >= 0) next else kept.lastIndex) to 0L
            }
        }
        val newOf = keptOld.withIndex().associate { (newIndex, old) -> old to newIndex }
        return queue.copy(
            items = kept,
            index = index,
            positionMs = position,
            shuffleOrder = queue.shuffleOrder?.mapNotNull { newOf[it] }
                ?.takeIf { isPermutation(it, kept.size) },
        )
    }

    /**
     * True when [order] visits every index of a [size]-item queue exactly once — the only shape
     * `DefaultShuffleOrder` accepts. Anything else read back from disk is discarded rather than
     * handed to the player.
     */
    fun isPermutation(order: List<Int>, size: Int): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (i in order) {
            if (i !in 0 until size || seen[i]) return false
            seen[i] = true
        }
        return true
    }
}
