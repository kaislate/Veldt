// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import com.kaislate.veldtplayer.data.settings.StoredTabs
import kotlin.math.abs

/**
 * The bottom bar's arrangement rules (player-sheet/server-tab spec, Round 2 → D), as pure
 * functions over [StoredTabs] so every rule — unknown ids, a server tab arriving later, the
 * start-tab fallback, the last shown tab — is a unit test rather than a device check.
 *
 * A tab's id IS its route ([Destinations.SONGS] …): the stored strings are the same ones the
 * nav graph already names, so there is no second vocabulary to keep in step, and a route renamed
 * in a later build simply becomes an unknown id, which every function here ignores.
 *
 * **What is stored is the user's intent; what is shown is derived.** The stored order keeps the
 * server tab's slot even while no server exists, and a hidden tab keeps its slot while hidden, so
 * adding a server back — or showing a tab again — returns it to where the user put it rather than
 * to the end. Every write path therefore goes through [reorderSubset], which moves only the tabs
 * a surface was actually showing and leaves every other slot where it was.
 */
object TabArrangement {

    /** Every tab, in the order a fresh install shows them. The server tab is last, so the five
     *  fixed tabs never move when it appears. */
    val DEFAULT_ORDER: List<String> = listOf(
        Destinations.SONGS,
        Destinations.ALBUMS,
        Destinations.ARTISTS,
        Destinations.PLAYLISTS,
        Destinations.FOLDERS,
        Destinations.SERVER,
    )

    /** "Open on" when the user has never chosen: Songs, as the app always opened before. */
    const val DEFAULT_START: String = Destinations.SONGS

    /**
     * [stored], with unknown ids and duplicates dropped and every known tab it is missing put
     * back **in its default position**: directly after the nearest tab that precedes it in
     * [DEFAULT_ORDER], or first if none does. So a server tab that did not exist when the order
     * was saved lands after Folders wherever Folders was moved to — last, for an untouched
     * order — and a tab added by a future build lands next to its default neighbour instead of
     * at an arbitrary end.
     */
    fun normalizedOrder(stored: List<String>): List<String> {
        val result = stored.filter { it in DEFAULT_ORDER }.distinct().toMutableList()
        DEFAULT_ORDER.forEachIndexed { defaultIndex, id ->
            if (id in result) return@forEachIndexed
            val predecessor = DEFAULT_ORDER.subList(0, defaultIndex).lastOrNull { it in result }
            val at = if (predecessor == null) 0 else result.indexOf(predecessor) + 1
            result.add(at, id)
        }
        return result
    }

    /** The tabs that can exist right now, in the user's order: all of them, minus the server tab
     *  while there is no server. Hidden tabs included — this is what Settings → Tabs lists. */
    fun available(tabs: StoredTabs, serverPresent: Boolean): List<String> =
        normalizedOrder(tabs.order).filter { it != Destinations.SERVER || serverPresent }

    /**
     * The bar's tabs, in order. Never empty: if every available tab is hidden — only reachable
     * when the one tab left shown was the server tab and the server was then removed — the first
     * available tab is shown anyway: the app needs somewhere to open, and a bar with no tabs has
     * no destination to be on.
     */
    fun shown(tabs: StoredTabs, serverPresent: Boolean): List<String> {
        val available = available(tabs, serverPresent)
        return available.filter { it !in tabs.hidden }.ifEmpty { available.take(1) }
    }

    /** Where the app opens: the chosen tab (else [DEFAULT_START]) if it is shown, otherwise the
     *  first shown tab — the owner's rule for a hidden or vanished (server removed) choice. */
    fun startTab(tabs: StoredTabs, serverPresent: Boolean): String {
        val shown = shown(tabs, serverPresent)
        return (tabs.start ?: DEFAULT_START).takeIf { it in shown } ?: shown.first()
    }

    /** Whether [id]'s switch may be turned OFF: it is shown, and it is not the last one. */
    fun canHide(tabs: StoredTabs, id: String, serverPresent: Boolean): Boolean {
        val shown = shown(tabs, serverPresent)
        return id in shown && shown.size > 1
    }

    /**
     * [tabs] with [id] hidden or shown. Hiding the last shown tab is refused (returns [tabs]
     * unchanged) — the Settings switch is disabled for it too, but the rule belongs here, where a
     * second caller cannot forget it. Unknown ids are refused as well, so nothing this build does
     * not know can get into the stored set.
     */
    fun withHidden(tabs: StoredTabs, id: String, hidden: Boolean, serverPresent: Boolean): StoredTabs {
        if (id !in DEFAULT_ORDER) return tabs
        if (hidden && !canHide(tabs, id, serverPresent)) return tabs
        val known = tabs.hidden.filterTo(HashSet()) { it in DEFAULT_ORDER }
        return tabs.copy(hidden = if (hidden) known + id else known - id)
    }

    /**
     * [full] with the ids in [subset] rearranged into [subset]'s order, each ending up in one of
     * the slots the subset occupied — every id NOT in [subset] keeps its exact index. This is how
     * the bar (which shows only shown tabs) and Settings (which shows only available ones) write
     * back an order without disturbing the slots they cannot see. Ids in [subset] that are not
     * in [full] are ignored.
     */
    fun reorderSubset(full: List<String>, subset: List<String>): List<String> {
        val moving = subset.filter { it in full }.distinct()
        val movingSet = moving.toSet()
        val queue = ArrayDeque(moving)
        return full.map { id -> if (id in movingSet) queue.removeFirst() else id }
    }

    /** [list] with the item at [from] moved to [to] (both clamped). */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (list.isEmpty() || from !in list.indices) return list
        val target = to.coerceIn(0, list.lastIndex)
        return list.toMutableList().apply { add(target, removeAt(from)) }
    }

    /** The index of the slot whose centre is nearest [x] — where a tab dragged along the bar
     *  would drop. -1 for no slots. */
    fun nearestSlot(centers: List<Float>, x: Float): Int =
        centers.indices.minByOrNull { abs(centers[it] - x) } ?: -1

    /**
     * Where a row dragged by [offset] from index [from] lands in a list of [count] rows of
     * [itemSize]: it moves one slot for every full row travelled past half-way. Rounded rather
     * than truncated so a row changes places when it covers more than half its neighbour — the
     * point at which it visibly sits more in the new slot than the old one.
     */
    fun dragTarget(from: Int, offset: Float, itemSize: Float, count: Int): Int {
        if (count <= 0) return -1
        if (itemSize <= 0f) return from.coerceIn(0, count - 1)
        return (from + Math.round(offset / itemSize)).coerceIn(0, count - 1)
    }
}
