// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/** What a folder's track list is ordered by. Filename is the default; see the KDoc on [FolderSort]. */
enum class TrackSort { FILENAME, TRACK_NUMBER, TITLE, DATE_MODIFIED }

/**
 * Ordering for the folder view — the one surface that is SUPPOSED to disagree with the tag views.
 *
 * **Filename is the default track order, not disc/track tags.** A user opens this view because the
 * tags are not to be trusted; applying them here re-imports the data they came to bypass, and when
 * they are absent `LibraryDerivations.sortAlbumTracks` degrades to alphabetical title order, which
 * scrambles an untagged album while the filenames sitting right there read `01 …`, `02 …`. Owner
 * decision, 2026-08-14.
 *
 * **[NATURAL] is numeric-aware and TOTAL.** `String.CASE_INSENSITIVE_ORDER` puts `Disc 10` before
 * `Disc 2` — wrong on exactly the names this feature exists to display. Case is folded for the
 * primary comparison and then fallen back on byte-exact, so two names differing only in case land
 * adjacent and stay DISTINCT. Folding case into identity is forbidden (global constraint 7); this
 * folds it for ORDER only, which is the whole difference.
 */
object FolderSort {

    val NATURAL: Comparator<String> = Comparator { a, b ->
        val c = compareNatural(a, b)
        if (c != 0) c else a.compareTo(b)   // byte-exact tiebreak keeps the comparator total
    }

    /** Digit runs compare as numbers, everything else char-by-char, case-insensitively. */
    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ia = i
                while (ia < a.length && a[ia].isDigit()) ia++
                var jb = j
                while (jb < b.length && b[jb].isDigit()) jb++
                // Compared as text with leading zeros stripped, so arbitrarily long runs cannot
                // overflow a Long the way parsing them would.
                val na = a.substring(i, ia).trimStart('0')
                val nb = b.substring(j, jb).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ia
                j = jb
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Directories, by name. The file-manager contract: directories first, never intermixed. */
    fun folders(nodes: List<FolderNode>): List<FolderNode> =
        nodes.sortedWith(compareBy(NATURAL) { it.name })

    fun tracks(songs: List<Song>, sort: TrackSort, descending: Boolean): List<Song> {
        val comparator: Comparator<Song> = when (sort) {
            TrackSort.FILENAME -> compareBy(NATURAL) { it.location()?.fileName.orEmpty() }
            TrackSort.TRACK_NUMBER -> compareBy<Song> { it.discNumber ?: Int.MAX_VALUE }
                .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                .thenBy(NATURAL) { it.location()?.fileName.orEmpty() }
            TrackSort.TITLE -> compareBy(NATURAL) { it.title }
            TrackSort.DATE_MODIFIED -> compareByDescending<Song> { it.dateModifiedSec }
                .thenBy(NATURAL) { it.location()?.fileName.orEmpty() }
        }
        val sorted = songs.sortedWith(comparator)
        return if (descending) sorted.reversed() else sorted
    }

    /**
     * This folder's direct tracks, then each child folder's, recursively — **depth-first,
     * pre-order**.
     *
     * The ordering is not incidental. `Album/` yields `Album/…`, then all of `Album/Disc 1/…`,
     * then all of `Album/Disc 2/…`. A breadth-first or globally-flat-sorted alternative
     * interleaves the two discs, which is the exact failure the folder view exists to repair.
     *
     * (The three globs above end in `…` rather than a star deliberately: Kotlin block comments
     * NEST, so a slash-star inside this KDoc opens a comment that never closes and the file does
     * not compile. Prose only — the behaviour described is unchanged.)
     */
    fun deepFlatten(node: FolderNode, sort: TrackSort, descending: Boolean): List<Song> {
        val out = ArrayList<Song>(node.deepSongCount)
        out += tracks(node.songs, sort, descending)
        for (child in folders(node.children)) out += deepFlatten(child, sort, descending)
        return out
    }
}
