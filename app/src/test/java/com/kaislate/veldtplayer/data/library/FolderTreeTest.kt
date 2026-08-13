// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree, derived from nothing but the song list.
 *
 * **Multi-volume behaviour is JVM-only coverage.** Neither device on this fleet has an SD card
 * (pre-flight survey, 2026-08-14: 80/80 rows are `external_primary`), so the property the whole
 * volume-qualified key design exists for cannot be exercised on hardware here. These tests are the
 * only thing standing behind it — treat them accordingly.
 */
class FolderTreeTest {

    private var nextId = 1L
    private fun song(
        relativeKey: String?,
        filePath: String? = null,
        durationMs: Long = 1000L,
        album: String = "b",
    ) = Song(
        id = nextId++, sourceId = "test", externalId = "e${nextId}", uri = "content://x",
        filePath = filePath, relativeKey = relativeKey,
        title = "t", artist = "a", album = album, albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = durationMs, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    @Test fun `a song lands in the folder its path names`() {
        val roots = FolderTree.build(listOf(song("external_primary:Music/Beck/a.mp3")))
        val beck = FolderTree.find(roots, "external_primary:Music/Beck")
        assertEquals(listOf("a.mp3"), beck?.songs?.map { it.relativeKey?.substringAfterLast('/') })
    }

    @Test fun `identically named directories on TWO VOLUMES never merge`() {
        // The collision composeRelativeKey's invariant item 1 exists to prevent, one layer up. Its
        // symptom in the UI is a folder half-filled from a card that is not even mounted.
        val roots = FolderTree.build(
            listOf(song("external_primary:Music/Beck/a.mp3"), song("1234-5678:Music/Beck/b.mp3"))
        )
        val primary = FolderTree.find(roots, "external_primary:Music/Beck")
        val card = FolderTree.find(roots, "1234-5678:Music/Beck")
        assertEquals(
            "two volumes' Music/Beck merged into one folder",
            listOf(1, 1),
            listOf(primary?.songs?.size, card?.songs?.size),
        )
    }

    @Test fun `directories differing ONLY in case stay two folders`() {
        val roots = FolderTree.build(
            listOf(song("external_primary:Music/beck/a.mp3"), song("external_primary:Music/Beck/b.mp3"))
        )
        assertEquals(
            "'beck' and 'Beck' merged — folder identity must stay byte-exact",
            listOf(1, 1),
            listOf(
                FolderTree.find(roots, "external_primary:Music/beck")?.songs?.size,
                FolderTree.find(roots, "external_primary:Music/Beck")?.songs?.size,
            ),
        )
    }

    @Test fun `deep counts aggregate this folder AND everything under it`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/Album/a.mp3", durationMs = 1000L),
            song("external_primary:Music/Album/Disc 1/b.mp3", durationMs = 2000L),
            song("external_primary:Music/Album/Disc 2/c.mp3", durationMs = 4000L),
        ))
        val album = FolderTree.find(roots, "external_primary:Music/Album")!!
        // `<Any>` on BOTH sides is load-bearing, not decoration. Without it the expected list is
        // `listOf(3, 7000L, 2, 1)`, whose untyped integer literals are widened to Long by the one
        // Long among them — it infers `List<Long>` = [3L, 7000L, 2L, 1L]. The actual list is
        // [Int, Long, Int, Int], so `3L != 3` and the assertion can never pass for ANY
        // implementation, while printing an identical-looking `[3, 7000, 2, 1]` on both sides.
        // Pinning T to Any leaves each literal at its natural type. Do not remove.
        assertEquals(
            listOf<Any>(3, 7000L, 2, 1),
            listOf<Any>(album.deepSongCount, album.deepDurationMs, album.deepFolderCount, album.songs.size),
        )
    }

    @Test fun `songs is DIRECT children only, never deep`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/Album/a.mp3"),
            song("external_primary:Music/Album/Disc 1/b.mp3"),
        ))
        val album = FolderTree.find(roots, "external_primary:Music/Album")!!
        assertEquals(1, album.songs.size)
    }

    @Test fun `a folder with no direct audio but audio descendants EXISTS and is not empty-looking`() {
        // Omitting these makes the tree unwalkable: Music/ itself usually holds no audio at all.
        val roots = FolderTree.build(listOf(song("external_primary:Music/Beck/a.mp3")))
        val music = FolderTree.find(roots, "external_primary:Music")!!
        assertEquals(
            listOf(0, 1),
            listOf(music.songs.size, music.deepSongCount),
        )
    }

    @Test fun `no folder with zero deep audio can exist ANYWHERE in the tree`() {
        // The invariant that stops empty rows from ever appearing (spec 7.4).
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/Beck/a.mp3"),
            song("external_primary:Download/b.mp3"),
            song(null, "/storage/emulated/0/c.mp3"),
        ))
        val empties = mutableListOf<String>()
        fun walk(n: FolderNode) {
            if (n.deepSongCount == 0) empties += n.key
            n.children.forEach(::walk)
        }
        roots.forEach(::walk)
        assertEquals("folders with no audio under them appeared: $empties", emptyList<String>(), empties)
    }

    @Test fun `a locationless song goes to Unfiled, never silently dropped`() {
        val roots = FolderTree.build(listOf(song(null, null), song("external_primary:Music/a.mp3")))
        val unfiled = roots.firstOrNull { it.key == UNFILED_KEY }
        assertEquals(1, unfiled?.songs?.size)
    }

    @Test fun `Unfiled is ABSENT when every song has a location`() {
        val roots = FolderTree.build(listOf(song("external_primary:Music/a.mp3")))
        assertTrue(
            "an empty Unfiled bucket must not render",
            roots.none { it.key == UNFILED_KEY },
        )
    }

    @Test fun `a volume-root track sits at the volume root, not in a blank-named folder`() {
        // The device-observed RELATIVE_PATH="/" case, end to end. A blank-named child here is the
        // failure PathSegments exists to prevent.
        val roots = FolderTree.build(listOf(song(null, "/storage/emulated/0/x.mp3")))
        val root = FolderTree.find(roots, "external_primary")!!
        assertEquals(
            listOf(1, emptyList<String>()),
            listOf(root.songs.size, root.children.map { it.name }),
        )
    }

    @Test fun `every song in the input appears exactly once in the tree`() {
        // The anti-silent-vanishing invariant, stated over the whole tree rather than per folder.
        val input = listOf(
            song("external_primary:Music/Beck/a.mp3"),
            song("external_primary:Music/b.mp3"),
            song(null, "/storage/emulated/0/c.mp3"),
            song(null, null),
        )
        val seen = mutableListOf<Long>()
        fun walk(n: FolderNode) { seen += n.songs.map { it.id }; n.children.forEach(::walk) }
        FolderTree.build(input).forEach(::walk)
        assertEquals(input.map { it.id }.sorted(), seen.sorted())
    }
}
