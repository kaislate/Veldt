// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which songs a set of hidden folders excludes, and which folders it marks (Step 5 spec §4).
 *
 * **Total tables, asserted whole.** Each test maps every case to its answer and compares the whole
 * map, so a failure names every case that moved rather than the first one, and a case that went
 * missing from the implementation's reach cannot hide behind one that did not.
 *
 * `Music/A` vs `Music/AB` is the case a string-prefix implementation gets wrong, and it is in every
 * table where it can be: it is the negative control for this task.
 */
class FolderExclusionTest {

    private val local = "local"

    private fun song(
        relativeKey: String? = null,
        filePath: String? = null,
        sourceId: String = local,
    ) = Song(
        id = 1, sourceId = sourceId, externalId = "e", uri = "content://x",
        filePath = filePath, relativeKey = relativeKey,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    private fun excluded(keys: Set<String>, songs: Map<String, Song>): Map<String, Boolean> {
        val exclusion = FolderExclusion(keys, local)
        return songs.mapValues { exclusion.isExcluded(it.value) }
    }

    @Test fun `a hidden folder covers itself and what is below it, on whole segments only`() {
        val songs = mapOf(
            "in the folder" to song("external_primary:Music/A/x.mp3"),
            "in a child" to song("external_primary:Music/A/B/x.mp3"),
            "in a grandchild" to song("external_primary:Music/A/B/C/x.mp3"),
            "in a sibling that shares a prefix" to song("external_primary:Music/AB/x.mp3"),
            "in the parent" to song("external_primary:Music/x.mp3"),
            "same path, another volume" to song("1234-5678:Music/A/x.mp3"),
            "a case-different folder" to song("external_primary:Music/a/x.mp3"),
        )
        assertEquals(
            mapOf(
                "in the folder" to true,
                "in a child" to true,
                "in a grandchild" to true,
                "in a sibling that shares a prefix" to false,
                "in the parent" to false,
                "same path, another volume" to false,
                "a case-different folder" to false,
            ),
            excluded(setOf("external_primary:Music/A"), songs),
        )
    }

    /**
     * The key and the song have to agree through [Song.location], whichever rung located it: a
     * `filePath`-located song (no relativeKey — a file at the volume root has none by construction)
     * must be covered by the same key a relativeKey-located song is.
     */
    @Test fun `a song located by its file path is matched the same way`() {
        val songs = mapOf(
            "card, by path" to song(filePath = "/storage/1234-5678/BACKUP/Downloads/x.mp3"),
            "card, prefix sibling, by path" to song(filePath = "/storage/1234-5678/BACKUP/DownloadsOld/x.mp3"),
            "card, by relativeKey" to song("1234-5678:BACKUP/Downloads/y.mp3"),
            "primary, same path" to song(filePath = "/storage/emulated/0/BACKUP/Downloads/x.mp3"),
        )
        assertEquals(
            mapOf(
                "card, by path" to true,
                "card, prefix sibling, by path" to false,
                "card, by relativeKey" to true,
                "primary, same path" to false,
            ),
            excluded(setOf("1234-5678:BACKUP/Downloads"), songs),
        )
    }

    /**
     * A volume root's key is the bare volume. It covers the whole volume — including a file at the
     * root itself — and not a volume whose name merely starts the same way.
     */
    @Test fun `hiding a volume root hides the whole volume and nothing else`() {
        val songs = mapOf(
            "at the root" to song(filePath = "/storage/1234-5678/x.mp3"),
            "deep" to song("1234-5678:Music/A/B/x.mp3"),
            "a volume sharing a prefix" to song("1234-56789:Music/x.mp3"),
            "primary" to song("external_primary:Music/x.mp3"),
        )
        assertEquals(
            mapOf(
                "at the root" to true,
                "deep" to true,
                "a volume sharing a prefix" to false,
                "primary" to false,
            ),
            excluded(setOf("1234-5678"), songs),
        )
    }

    /**
     * Only local songs are ever excluded. The remote song here carries a server path that
     * [Song.location] parses into exactly the hidden folder — a Navidrome path with a `:` in it —
     * so only the source check keeps it in the library.
     */
    @Test fun `remote songs and songs with no location are never excluded`() {
        val songs = mapOf(
            "remote, path parses to the hidden folder" to
                song("external_primary:Music/A/x.mp3", sourceId = "navidrome-1"),
            "local, no location" to song(),
            "local, in the folder" to song("external_primary:Music/A/x.mp3"),
        )
        assertEquals(
            mapOf(
                "remote, path parses to the hidden folder" to false,
                "local, no location" to false,
                "local, in the folder" to true,
            ),
            excluded(setOf("external_primary:Music/A", UNFILED_KEY), songs),
        )
    }

    @Test fun `no keys, or only keys that name no folder, exclude nothing`() {
        val s = song("external_primary:Music/A/x.mp3")
        assertEquals(
            mapOf(
                "empty" to listOf(true, false),
                "unfiled bucket" to listOf(true, false),
                "leading separator" to listOf(true, false),
                "empty segment" to listOf(true, false),
            ),
            mapOf(
                "empty" to emptySet(),
                "unfiled bucket" to setOf(UNFILED_KEY),
                "leading separator" to setOf(":Music/A"),
                "empty segment" to setOf("external_primary:Music//A"),
            ).mapValues { (_, keys) ->
                val exclusion = FolderExclusion(keys, local)
                listOf(exclusion.isEmpty, exclusion.isExcluded(s))
            },
        )
    }

    /**
     * The folder tree's marks: a hidden folder and its descendants are marked, siblings and the
     * prefix-sharing folder are not, and every folder is still THERE — marking removes nothing.
     */
    @Test fun `the tree marks a hidden folder and its descendants and keeps every folder`() {
        val exclusion = FolderExclusion(setOf("external_primary:Music/A"), local)
        val tree = FolderTree.build(
            listOf(
                song("external_primary:Music/A/x.mp3"),
                song("external_primary:Music/A/B/x.mp3"),
                song("external_primary:Music/AB/x.mp3"),
                song("external_primary:Music/x.mp3"),
                song(),
            ),
            exclusion::covers,
        )
        val marks = LinkedHashMap<String, Boolean>()
        fun walk(node: FolderNode) {
            marks[node.key] = node.hidden
            node.children.forEach(::walk)
        }
        tree.forEach(::walk)
        assertEquals(
            mapOf(
                "external_primary" to false,
                "external_primary:Music" to false,
                "external_primary:Music/A" to true,
                "external_primary:Music/A/B" to true,
                "external_primary:Music/AB" to false,
                UNFILED_KEY to false,
            ),
            marks,
        )
        assertEquals(
            "the marks disagree with isHidden on the finished nodes",
            marks,
            LinkedHashMap<String, Boolean>().also { out ->
                fun walk(node: FolderNode) {
                    out[node.key] = exclusion.isHidden(node)
                    node.children.forEach(::walk)
                }
                tree.forEach(::walk)
            },
        )
    }

    /**
     * **The key IS [FolderNode.key], and a song derives the same key.** For every folder the tree
     * builds, hiding that node's key excludes exactly the songs the tree placed in its subtree —
     * which is the whole claim that no second identity is needed. Covers both location rungs, a
     * volume root, a file at a volume root, and a segment containing `:`.
     */
    @Test fun `hiding a node's own key excludes exactly the songs in its subtree`() {
        val songs = listOf(
            song("external_primary:Music/A/x.mp3"),
            song("external_primary:Music/A/B/x.mp3"),
            song("external_primary:Music/AB/x.mp3"),
            song("external_primary:Live: 1999/x.mp3"),
            song(filePath = "/storage/emulated/0/root.mp3"),
            song(filePath = "/storage/1234-5678/BACKUP/Downloads/x.mp3"),
            song("1234-5678:BACKUP/y.mp3"),
        ).mapIndexed { i, s -> s.copy(id = i.toLong()) }
        val nodes = ArrayList<FolderNode>()
        fun walk(node: FolderNode) {
            nodes += node
            node.children.forEach(::walk)
        }
        FolderTree.build(songs).forEach(::walk)
        fun deep(node: FolderNode): List<Long> =
            node.songs.map { it.id } + node.children.flatMap(::deep)

        val mismatches = nodes.filter { node ->
            val exclusion = FolderExclusion(setOf(node.key), local)
            songs.filter(exclusion::isExcluded).map { it.id }.sorted() != deep(node).sorted()
        }.map { it.key }
        assertEquals("these node keys do not round-trip through Song.location", emptyList<String>(), mismatches)
        assertEquals("the fixture lost its folders", 9, nodes.size)
    }

    @Test fun `a key reads as its volume label and path`() {
        val label = { volume: String -> if (volume == "1234-5678") "SD card" else "?$volume" }
        assertEquals(
            listOf("SD card › BACKUP › Downloads", "SD card", ":Music/A"),
            listOf("1234-5678:BACKUP/Downloads", "1234-5678", ":Music/A")
                .map { FolderExclusion.describe(it, label) },
        )
    }
}
