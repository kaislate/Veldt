// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.FolderTree
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The row caption — `3 folders · 42 tracks · 2h 51m` — and the covers a folder's mosaic is built
 * from.
 *
 * Pure: both are functions of a [com.kaislate.veldtplayer.data.library.FolderNode] and nothing else,
 * which is why they are testable at all rather than being spelled inline in the row.
 */
class FolderCaptionTest {

    private var nextId = 1L

    private fun song(
        relativeKey: String,
        album: String = "b",
        durationMs: Long = 0L,
        hasEmbeddedArt: Boolean = false,
    ) = Song(
        id = nextId++, sourceId = "test", externalId = "e$nextId", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = "t", artist = "a", album = album, albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = durationMs, dateModifiedSec = 0L, hasEmbeddedArt = hasEmbeddedArt,
    )

    private fun node(vararg songs: Song) = FolderTree.build(songs.toList()).single()

    /**
     * The whole caption, in one assertion, on a folder that has all three clauses.
     *
     * **Both counts are DEEP, and the fixture separates deep from direct for each of them.** `Music`
     * holds no track of its own, so the track clause reads "0 tracks" off the direct list. And
     * `Sea Change` is a GRANDchild: `Music` has two children but three folders under it, so
     * `node.children.size` and `node.deepFolderCount` are different numbers here. Without that
     * grandchild the two are equal and the folder clause is pinned at neither depth — the shape
     * this test's own message claims to exclude.
     */
    @Test fun `a parent folder reports its deep folders, tracks and running time`() {
        val root = node(
            song(
                "external_primary:Music/Beck/Sea Change/a.mp3",
                durationMs = 2 * 3_600_000L + 51 * 60_000L,
            ),
            song("external_primary:Music/Radiohead/b.mp3", durationMs = 30_000L),
        )
        // `external_primary` -> `Music` -> {Beck -> Sea Change, Radiohead}. Asked of `Music`:
        // two children, three descendants.
        val music = root.children.single()
        assertEquals(
            "the caption is not built from the DEEP aggregates, or a clause is missing",
            "3 folders · 2 tracks · 2h 51m",
            folderCaption(music),
        )
    }

    /**
     * A leaf omits the folder clause entirely — `0 folders` is a fact nobody asked for — and
     * singular/plural is not "1 folders". The duration clause is dropped when the aggregate is
     * zero, which is what MediaStore reporting no duration produces; `· 0m` would be a claim.
     */
    @Test fun `a leaf omits the folder clause, and a zero running time omits its own`() {
        val leaf = node(song("external_primary:Music/x.mp3")).children.single()
        val oneEach = node(
            song("external_primary:Music/Beck/a.mp3", durationMs = 60_000L),
        ).children.single()
        assertEquals(
            "a zero clause was printed, or a singular clause was pluralised",
            listOf("1 track", "1 folder · 1 track · 1m"),
            listOf(folderCaption(leaf), folderCaption(oneEach)),
        )
    }

    /** Rounds DOWN, so a caption never claims more music than the folder holds. */
    @Test fun `a running time under an hour drops the hour clause and never rounds up`() {
        assertEquals(
            "hours and minutes are not formatted as 'Nh Nm', or the remainder rounds up",
            listOf("0m", "59m", "1h 0m", "2h 51m"),
            listOf(
                folderDuration(59_999L),
                folderDuration(59 * 60_000L + 59_999L),
                folderDuration(3_600_000L),
                folderDuration(2 * 3_600_000L + 51 * 60_000L + 59_999L),
            ),
        )
    }

    /**
     * The mosaic's covers come from the folder's DESCENDANTS, not its direct tracks — a parent of
     * six album folders holds no track of its own and would otherwise draw a blank tile.
     *
     * Asserted on WHICH tracks, not how many: one per distinct album key, and within an album the
     * one [coverTrack] chooses (embedded art first, lowest id to break the tie). A walk that took
     * the first track of each folder would return the same NUMBER of covers and the wrong ones.
     */
    @Test fun `a parent folder's covers are one per album among its descendants`() {
        val beckPlain = song("external_primary:Music/Beck/a.mp3", album = "Sea Change")
        val beckArt = song(
            "external_primary:Music/Beck/b.mp3", album = "Sea Change", hasEmbeddedArt = true,
        )
        val radiohead = song("external_primary:Music/Radiohead/c.mp3", album = "Kid A")
        val music = node(beckPlain, beckArt, radiohead).children.single()
        assertEquals(
            "the covers are not one-per-album over the descendants, or coverTrack was bypassed",
            listOf(beckArt.id, radiohead.id),
            folderCovers(music).map { it.id },
        )
    }
}
