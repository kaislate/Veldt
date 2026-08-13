// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ordering, which is where the folder view is SUPPOSED to disagree with the tag views.
 *
 * `String.CASE_INSENSITIVE_ORDER` — which `LibraryDerivations.ALBUM_ORDER` uses — puts `Disc 10`
 * before `Disc 2`, and it gets that wrong on exactly the directory names this feature exists to
 * display. The comparator here must be numeric-aware AND total: fold case for the primary
 * comparison, then fall back byte-exact, so two names differing only in case stay adjacent and
 * DISTINCT rather than collapsing.
 */
class FolderSortTest {

    private var nextId = 1L
    private fun song(
        fileName: String,
        title: String = fileName,
        track: Int? = null,
        disc: Int? = null,
        modified: Long = 0L,
    ) = Song(
        id = nextId++, sourceId = "test", externalId = "e${nextId}", uri = "content://x",
        filePath = null, relativeKey = "external_primary:Music/$fileName",
        title = title, artist = "a", album = "b", albumArtist = null,
        trackNumber = track, discNumber = disc, year = null,
        durationMs = 0L, dateModifiedSec = modified, hasEmbeddedArt = false,
    )

    private fun folder(name: String) = FolderNode(
        key = "external_primary:Music/$name", volume = "external_primary",
        segments = listOf("Music", name), name = name,
        children = emptyList(), songs = emptyList(),
        deepSongCount = 1, deepDurationMs = 0L, deepFolderCount = 0,
    )

    @Test fun `natural order puts Disc 2 before Disc 10`() {
        val sorted = FolderSort.folders(listOf(folder("Disc 10"), folder("Disc 2"), folder("Disc 1")))
        assertEquals(listOf("Disc 1", "Disc 2", "Disc 10"), sorted.map { it.name })
    }

    @Test fun `natural order handles CD1 through CD10`() {
        val sorted = FolderSort.folders(
            listOf(folder("CD10"), folder("CD1"), folder("CD9"), folder("CD2"))
        )
        assertEquals(listOf("CD1", "CD2", "CD9", "CD10"), sorted.map { it.name })
    }

    @Test fun `the comparator is TOTAL — two names differing only in case both survive`() {
        val sorted = FolderSort.folders(listOf(folder("beck"), folder("Beck")))
        assertEquals(
            "a case-only difference collapsed — both folders must survive, adjacent and distinct",
            2, sorted.size,
        )
        assertEquals(setOf("beck", "Beck"), sorted.map { it.name }.toSet())
    }

    @Test fun `filename order uses the numbers on the files, not the tags`() {
        // The whole premise: tags say otherwise and the filenames are right.
        val sorted = FolderSort.tracks(
            listOf(
                song("10 - j.mp3", title = "Aaa", track = 1),
                song("02 - b.mp3", title = "Bbb", track = 2),
                song("01 - a.mp3", title = "Ccc", track = 3),
            ),
            TrackSort.FILENAME, descending = false,
        )
        assertEquals(listOf("01 - a.mp3", "02 - b.mp3", "10 - j.mp3"), sorted.map { it.fileNameOrEmpty() })
    }

    @Test fun `track-number order is available and uses disc then track`() {
        val sorted = FolderSort.tracks(
            listOf(song("c.mp3", track = 1, disc = 2), song("a.mp3", track = 2, disc = 1)),
            TrackSort.TRACK_NUMBER, descending = false,
        )
        assertEquals(listOf("a.mp3", "c.mp3"), sorted.map { it.fileNameOrEmpty() })
    }

    @Test fun `descending reverses every sort`() {
        val songs = listOf(song("01 - a.mp3"), song("02 - b.mp3"))
        assertEquals(
            listOf("02 - b.mp3", "01 - a.mp3"),
            FolderSort.tracks(songs, TrackSort.FILENAME, descending = true).map { it.fileNameOrEmpty() },
        )
    }

    @Test fun `the deep flatten is DEPTH-FIRST PRE-ORDER — discs never interleave`() {
        // The case that motivates the whole feature. A breadth-first or globally-flat-sorted
        // alternative interleaves the two discs, which is the exact failure this repairs.
        val album = FolderNode(
            key = "external_primary:Music/Album", volume = "external_primary",
            segments = listOf("Music", "Album"), name = "Album",
            songs = listOf(song("00 - intro.mp3")),
            children = listOf(
                FolderNode(
                    key = "external_primary:Music/Album/Disc 2", volume = "external_primary",
                    segments = listOf("Music", "Album", "Disc 2"), name = "Disc 2",
                    children = emptyList(),
                    songs = listOf(song("d2a.mp3"), song("d2b.mp3")),
                    deepSongCount = 2, deepDurationMs = 0L, deepFolderCount = 0,
                ),
                FolderNode(
                    key = "external_primary:Music/Album/Disc 1", volume = "external_primary",
                    segments = listOf("Music", "Album", "Disc 1"), name = "Disc 1",
                    children = emptyList(),
                    songs = listOf(song("d1a.mp3"), song("d1b.mp3")),
                    deepSongCount = 2, deepDurationMs = 0L, deepFolderCount = 0,
                ),
            ),
            deepSongCount = 5, deepDurationMs = 0L, deepFolderCount = 2,
        )
        assertEquals(
            listOf("00 - intro.mp3", "d1a.mp3", "d1b.mp3", "d2a.mp3", "d2b.mp3"),
            FolderSort.deepFlatten(album, TrackSort.FILENAME, descending = false)
                .map { it.fileNameOrEmpty() },
        )
    }

    private fun Song.fileNameOrEmpty(): String = location()?.fileName.orEmpty()
}
