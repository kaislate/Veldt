// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** Root elision (owner decision, 2026-08-14): skip pass-through ancestors at the TOP only. */
class FolderElisionTest {

    private var nextId = 1L
    private fun song(relativeKey: String) = Song(
        id = nextId++, sourceId = "test", externalId = "e${nextId}", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    @Test fun `a library living entirely under Music opens on the ARTIST folders`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/Beck/a.mp3"),
            song("external_primary:Music/Radiohead/b.mp3"),
        ))
        val elided = FolderTree.elideRoots(roots)
        assertEquals(listOf("Beck", "Radiohead"), elided.single().displayRoot.children.map { it.name }.sorted())
    }

    @Test fun `what was elided is REPORTED so the breadcrumb can show it`() {
        val roots = FolderTree.build(listOf(song("external_primary:Music/Beck/a.mp3")))
        assertEquals(
            listOf("external_primary", "Music"),
            FolderTree.elideRoots(roots).single().elided.map { it.name },
        )
    }

    @Test fun `elision STOPS at a folder holding audio of its own`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/loose.mp3"),
            song("external_primary:Music/Beck/a.mp3"),
        ))
        val display = FolderTree.elideRoots(roots).single().displayRoot
        assertEquals(
            "elision walked past a folder with direct audio, hiding 'loose.mp3'",
            listOf(1, "Music"),
            listOf(display.songs.size, display.name),
        )
    }

    @Test fun `elision STOPS where the tree branches`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/a.mp3"),
            song("external_primary:Download/b.mp3"),
        ))
        val display = FolderTree.elideRoots(roots).single().displayRoot
        assertEquals(
            listOf("Download", "Music"),
            display.children.map { it.name }.sorted(),
        )
    }

    @Test fun `TWO volumes keep the volume level — R1 applies only to a single volume`() {
        val roots = FolderTree.build(listOf(
            song("external_primary:Music/a.mp3"),
            song("1234-5678:Music/b.mp3"),
        ))
        val elided = FolderTree.elideRoots(roots)
        assertEquals(2, elided.size)
        // The count above cannot fail on its own: elideRoots maps 1:1, so `size` is invariant under
        // every implementation of this contract, correct or not. What has to hold is that the two
        // volumes are elided INDEPENDENTLY — each opening on its own `Music` while still carrying
        // the volume that labels its row. A no-op, or an R1 gate keyed on `roots.size`, leaves both
        // display roots named after the volume instead.
        assertEquals(
            "the two volumes did not elide independently",
            listOf("1234-5678" to "Music", "external_primary" to "Music"),
            elided.map { it.displayRoot.volume to it.displayRoot.name }.sortedBy { it.first },
        )
    }

    @Test fun `Unfiled is never elided away`() {
        val roots = FolderTree.build(listOf(song("external_primary:Music/a.mp3")) + listOf(
            Song(
                id = 99L, sourceId = "test", externalId = "e99", uri = "content://x",
                filePath = null, relativeKey = null,
                title = "t", artist = "a", album = "b", albumArtist = null,
                trackNumber = null, discNumber = null, year = null,
                durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
            )
        ))
        val elided = FolderTree.elideRoots(roots)
        assertEquals(
            "Unfiled disappeared — a synthetic bucket must survive elision or its songs vanish",
            2, elided.size,
        )
        // As above, the count alone is invariant and cannot fail — including under the mutation of
        // dropping elideRoots' UNFILED_KEY guard, which was executed and stayed green. The reachable
        // failure this fixture guards is the bucket being COUNTED as a volume: this library has ONE
        // real volume, so it must still elide and open on `Music`, not on `external_primary`.
        assertEquals(
            "the Unfiled bucket was counted as a volume — the one real volume stopped eliding",
            listOf("Music", "Unfiled"),
            elided.map { it.displayRoot.name }.sorted(),
        )
    }

    @Test fun `the inlined primary volume name matches MediaStore's constant`() {
        // SongLocation inlines this to stay framework-free and JVM-testable. If the platform ever
        // changes the value, every rung-2 volume silently becomes wrong — so it is asserted, not
        // assumed. android.provider.MediaStore constants are plain String statics and resolve in
        // unit tests without Robolectric.
        assertEquals(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY, VOLUME_PRIMARY)
    }
}
