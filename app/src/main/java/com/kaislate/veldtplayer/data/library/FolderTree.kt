// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/** The bucket for songs with no derivable location. NUL-prefixed so no real volume can collide. */
const val UNFILED_KEY: String = "\u0000unfiled"

/**
 * One directory, and everything the UI needs to draw a row for it without walking the tree again.
 *
 * [songs] is DIRECT children only; the `deep*` fields are aggregates over this folder and all of
 * its descendants. Both are needed and they are not the same question — the row caption reports
 * the deep counts while "play this folder" (shallow) uses the direct list. Computing the
 * aggregates during the single bottom-up build costs nothing; recomputing them per row at render
 * time would be a tree walk per frame.
 */
data class FolderNode(
    val key: String,
    val volume: String,
    val segments: List<String>,
    val name: String,
    val children: List<FolderNode>,
    val songs: List<Song>,
    val deepSongCount: Int,
    val deepDurationMs: Long,
    val deepFolderCount: Int,
)

/**
 * A volume's root as the UI should present it, plus the ancestors elision skipped.
 *
 * [elided] is not decoration — the breadcrumb renders it, which is what keeps elision from ever
 * costing the user the truth about where they are.
 */
data class FolderRoot(val displayRoot: FolderNode, val elided: List<FolderNode>)

/**
 * The library as it is on disk, derived from the song list and nothing else.
 *
 * **No filesystem access** (global constraint 5) — that is not a simplification but the only
 * design available under scoped storage, where enumerating the directory tree directly under
 * `/storage/emulated/0` returns null for a non-legacy app. A tree fed by a different enumeration
 * would also disagree with the Songs tab, and two disagreeing views of one library is worse than a
 * missing view.
 *
 * **The tree has one root per volume**, structurally. `LocalSource` queries `VOLUME_EXTERNAL`,
 * which spans primary storage and removable SD on API 29+, so this is not an edge case.
 */
object FolderTree {

    /** `volume:seg/seg/…`, byte-exact. A song's folder key is a prefix of its own location. */
    fun folderKey(volume: String, segments: List<String>): String =
        if (segments.isEmpty()) volume
        else volume + LocalSource.VOLUME_SEPARATOR + segments.joinToString("/")

    /** A mutable staging node; the public [FolderNode] is built from it bottom-up. */
    private class Builder(val volume: String, val segments: List<String>) {
        val children = LinkedHashMap<String, Builder>()
        val songs = ArrayList<Song>()
    }

    fun build(songs: List<Song>): List<FolderNode> {
        val volumes = LinkedHashMap<String, Builder>()
        val unfiled = ArrayList<Song>()

        for (song in songs) {
            val loc = song.location()
            if (loc == null) { unfiled += song; continue }
            var node = volumes.getOrPut(loc.volume) { Builder(loc.volume, emptyList()) }
            for ((depth, segment) in loc.segments.withIndex()) {
                node = node.children.getOrPut(segment) {
                    Builder(loc.volume, loc.segments.take(depth + 1))
                }
            }
            node.songs += song
        }

        val roots = ArrayList<FolderNode>(volumes.size + 1)
        volumes.values.mapTo(roots) { freeze(it) }
        if (unfiled.isNotEmpty()) {
            roots += FolderNode(
                key = UNFILED_KEY, volume = UNFILED_KEY, segments = emptyList(),
                name = "Unfiled", children = emptyList(), songs = unfiled,
                deepSongCount = unfiled.size,
                deepDurationMs = unfiled.sumOf { it.durationMs },
                deepFolderCount = 0,
            )
        }
        return roots
    }

    /** Bottom-up, so every aggregate is computed exactly once. */
    private fun freeze(b: Builder): FolderNode {
        val children = b.children.values.map { freeze(it) }
        return FolderNode(
            key = folderKey(b.volume, b.segments),
            volume = b.volume,
            segments = b.segments,
            name = b.segments.lastOrNull() ?: b.volume,
            children = children,
            songs = b.songs,
            deepSongCount = b.songs.size + children.sumOf { it.deepSongCount },
            deepDurationMs = b.songs.sumOf { it.durationMs } + children.sumOf { it.deepDurationMs },
            deepFolderCount = children.size + children.sumOf { it.deepFolderCount },
        )
    }

    /**
     * Fold away pass-through ancestors **at the top of each volume only**.
     *
     * A library that lives entirely in `Music/` opens on the artist folders rather than on a single
     * row reading `Music`. Elision stops at the first folder that either holds audio of its own or
     * has more than one child — walking past either would hide something.
     *
     * Honestly unstable in one narrow way: dropping a file into `Download/` moves the elided root
     * back to the volume root and the rows gain a level. Accepted, because it affects one level at
     * the top rather than the whole tree, the breadcrumb keeps it truthful, and the alternative is
     * a mandatory tap through `Internal storage → Music` every single day. Owner decision,
     * 2026-08-14.
     *
     * Interior chains are deliberately NOT collapsed — see the class KDoc.
     */
    fun elideRoots(roots: List<FolderNode>): List<FolderRoot> = roots.map { root ->
        // The Unfiled bucket is synthetic and has no ancestors to skip.
        //
        // **This guard is currently unreachable and is kept deliberately.** [build] only emits the
        // bucket when it has at least one song and never gives it children, so the loop below
        // already declines to walk it and removing this line changes no observable behaviour —
        // verified by executing that mutation, 2026-08-13: all six elision tests stayed green. It
        // stays because the invariant it depends on lives in another function: the day [build]
        // emits an empty or child-bearing bucket, eliding it would silently swallow the one root
        // whose songs have nowhere else to appear. Do not read its presence as evidence that the
        // Unfiled tests are pinning it — they are not, and cannot.
        if (root.key == UNFILED_KEY) return@map FolderRoot(root, emptyList())
        val skipped = ArrayList<FolderNode>()
        var node = root
        while (node.songs.isEmpty() && node.children.size == 1) {
            skipped += node
            node = node.children.single()
        }
        FolderRoot(node, skipped)
    }

    /** The node with [key], or null. Linear in the tree; callers cache (global constraint 14). */
    fun find(roots: List<FolderNode>, key: String): FolderNode? {
        for (root in roots) {
            find(root, key)?.let { return it }
        }
        return null
    }

    private fun find(node: FolderNode, key: String): FolderNode? {
        if (node.key == key) return node
        for (child in node.children) find(child, key)?.let { return it }
        return null
    }
}
