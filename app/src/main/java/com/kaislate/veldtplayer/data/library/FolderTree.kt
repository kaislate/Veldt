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
 * The library as it is on disk, derived from the song list and nothing else.
 *
 * **No filesystem access** (global constraint 5) — that is not a simplification but the only
 * design available under scoped storage, where `File.listFiles()` on `/storage/emulated/0` returns
 * null for a non-legacy app. A tree fed by a different enumeration would also disagree with the
 * Songs tab, and two disagreeing views of one library is worse than a missing view.
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
