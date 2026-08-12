// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.playlist.PlaylistTrack
import com.kaislate.veldtplayer.data.playlist.db.PlaylistEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playlist mosaic, pinned as a LAYOUT rather than as a count.
 *
 * "Never 2-with-a-hole" is a claim about pixels, and a suite that only counted slots would pass
 * with every tile drawn in the top-left corner. So the rectangles themselves are asserted —
 * which cover gets which part of the box, and that the parts tile the box exactly — and the
 * "one cover fills the whole header" case is asserted as `MosaicRect(0f, 0f, 1f, 1f)`, a value a
 * mutation that parked a lone cover in a quadrant would fail.
 *
 * The de-duplication test runs the REAL pipeline, `coversOf` into `mosaicSlots`, because that is
 * where the guarantee lives: `mosaicSlots` deliberately does not de-duplicate a second time (see
 * its own docs), so asserting the collapse on hand-written input would assert nothing.
 */
class PlaylistMosaicTest {

    // ------------------------------------------------------------------------ the five layouts

    @Test fun `four distinct covers fill four slots`() {
        val covers = listOf("a", "b", "c", "d")
        assertEquals(covers, mosaicSlots(covers))
        // And in the four quadrants, in reading order — a "cap" that kept four covers but drew
        // them all in one corner would satisfy the line above on its own.
        assertEquals(
            listOf(
                MosaicTile("a", MosaicRect(0f, 0f, 0.5f, 0.5f)),
                MosaicTile("b", MosaicRect(0.5f, 0f, 1f, 0.5f)),
                MosaicTile("c", MosaicRect(0f, 0.5f, 0.5f, 1f)),
                MosaicTile("d", MosaicRect(0.5f, 0.5f, 1f, 1f)),
            ),
            mosaicTiles(covers),
        )
    }

    /**
     * Three covers in a four-cell grid is a tile with a hole punched in one corner. The layout
     * becomes a leading half plus two trailing quarters instead — asserted as the rectangles,
     * and as the property that makes it a fallback rather than a gap: the three of them cover
     * the box completely.
     */
    @Test fun `three covers do not leave a hole — the layout falls back to three-up`() {
        val tiles = mosaicTiles(listOf("a", "b", "c"))
        assertEquals(3, tiles.size)
        assertTiles(tiles)
        // The record the playlist opens with leads, at half the whole box — twice either of the
        // other two, which is what stops this reading as a grid that lost a cell.
        assertEquals("a", tiles[0].cover)
        assertEquals(0.5f, tiles[0].rect.area, TOLERANCE)
        assertEquals(0.25f, tiles[1].rect.area, TOLERANCE)
        assertEquals(0.25f, tiles[2].rect.area, TOLERANCE)
        assertEquals(MosaicRect(0f, 0f, 0.5f, 1f), tiles[0].rect)
    }

    /**
     * The LAYOUT claim, not a comment: one cover is the whole box.
     *
     * `MosaicRect(0f, 0f, 1f, 1f)` is the literal a mutation has to break. A lone cover placed
     * in the top-left quadrant — the shape this would take if the four-up rects were reused for
     * every count — still yields exactly one tile and would pass any count assertion.
     */
    @Test fun `one cover fills the whole header rather than sitting in a corner`() {
        val tiles = mosaicTiles(listOf("only"))
        assertEquals(1, tiles.size)
        assertEquals("only", tiles.single().cover)
        assertEquals(MosaicRect(0f, 0f, 1f, 1f), tiles.single().rect)
        assertEquals(1f, tiles.single().rect.area, TOLERANCE)
    }

    /**
     * Eight tracks off one record, then three more records: the mosaic must show FOUR DIFFERENT
     * covers, not four copies of the one the playlist opens with.
     *
     * Asserted on which albums land in the slots rather than on how many there are — four copies
     * of "First" is also a list of size four. This runs `coversOf` into `mosaicSlots` on purpose:
     * `coversOf` is where the album-distinct guarantee lives, and the negative control for this
     * test removes its `distinctBy`.
     */
    @Test fun `duplicate album covers are collapsed before slotting`() {
        val tracks = (0 until 8).map { resolved(song(album = "First")) } +
            resolved(song(album = "Second")) +
            resolved(song(album = "Third")) +
            resolved(song(album = "Fourth"))
        val slots = mosaicSlots(PlaylistPresentation.coversOf(tracks))
        assertEquals(listOf("First", "Second", "Third", "Fourth"), slots.map { it.album })
    }

    @Test fun `an empty playlist yields no slots and the caller draws the empty state`() {
        assertEquals(emptyList<String>(), mosaicSlots(emptyList<String>()))
        assertEquals(emptyList<MosaicTile<String>>(), mosaicTiles(emptyList<String>()))
    }

    // ------------------------------------------------------- the rule the five cases come from

    /**
     * The case the five named tests do not cover, and the one the whole rule exists for.
     *
     * Two covers in a 2x2 is a hole; two half-width strips is a mosaic made of a playlist that
     * has one record's worth of identity. Neither, so the first cover fills the box.
     */
    @Test fun `two covers draw one cover full bleed rather than half a grid`() {
        val tiles = mosaicTiles(listOf("a", "b"))
        assertEquals(1, tiles.size)
        assertEquals("a", tiles.single().cover)
        assertEquals(MosaicRect(0f, 0f, 1f, 1f), tiles.single().rect)
    }

    /** 0, 1, 3 or 4 — for every number of covers a playlist could ever offer. */
    @Test fun `no number of covers ever produces a two-slot layout`() {
        (0..8).forEach { count ->
            val size = mosaicSlots(covers(count)).size
            assertTrue("$count covers produced $size slots", size in setOf(0, 1, 3, 4))
            assertNotEquals("$count covers produced a two-slot layout", 2, size)
        }
    }

    /**
     * The property behind "never a hole", checked for every count: the tiles are inside the box,
     * they do not overlap, and their areas add up to the whole of it.
     *
     * A layout that dropped a rect, shrank one, or stacked two on top of each other fails here
     * even if every count in the suite still matches.
     */
    @Test fun `every layout tiles the box exactly — no hole and no overlap`() {
        (0..8).forEach { count -> assertTiles(mosaicTiles(covers(count))) }
    }

    /**
     * The tiles and the slots are the same list.
     *
     * `mosaicTiles` zips the chosen covers against the rectangles for their count, and a zip
     * against a shorter list truncates SILENTLY — so a slot count with no matching layout would
     * drop covers on the floor while `mosaicSlots` went on reporting them.
     */
    @Test fun `mosaicTiles agrees with mosaicSlots about how many tiles there are`() {
        (0..8).forEach { count ->
            val input = covers(count)
            assertEquals(
                "$count covers",
                mosaicSlots(input),
                mosaicTiles(input).map { it.cover },
            )
        }
    }

    /**
     * The FIRST covers, in playlist order. A mosaic that took the last four, or sorted them,
     * would also produce four tiles — the same shape of hole the `COVER_LIMIT` tautology left.
     */
    @Test fun `the slots are the first covers, in playlist order`() {
        assertEquals(listOf("a", "b", "c", "d"), mosaicSlots(listOf("a", "b", "c", "d", "e", "f")))
    }

    // ------------------------------------------------------------------------- the tile letter

    /**
     * A tile whose artwork will not load falls back to its ALBUM's letter, not the playlist's.
     * Four tiles all drawing the playlist's initial is the generic glyph again, four times over.
     */
    @Test fun `a tile with no artwork is lettered by its own album`() {
        assertEquals('B', tileInitial(song(album = "Bloom"), fallback = 'M'))
        assertEquals('7', tileInitial(song(album = "77"), fallback = 'M'))
    }

    /** Only a record with no album tag at all borrows the playlist's letter. */
    @Test fun `an untagged album borrows the playlist's letter`() {
        assertEquals('M', tileInitial(song(album = ""), fallback = 'M'))
        assertEquals('M', tileInitial(song(album = "<unknown>"), fallback = 'M'))
    }

    // -------------------------------------------------------------------------------- fixtures

    /** Every rect is inside the unit box, none overlap, and together they are the whole box. */
    private fun assertTiles(tiles: List<MosaicTile<*>>) {
        tiles.forEach { tile ->
            val r = tile.rect
            assertTrue("$r escapes the box", r.left >= 0f && r.top >= 0f && r.right <= 1f && r.bottom <= 1f)
            assertTrue("$r has no area", r.width > 0f && r.height > 0f)
        }
        for (i in tiles.indices) {
            for (j in i + 1 until tiles.size) {
                assertTrue(
                    "${tiles[i].rect} overlaps ${tiles[j].rect}",
                    !overlap(tiles[i].rect, tiles[j].rect),
                )
            }
        }
        val covered = tiles.sumOf { it.rect.area.toDouble() }.toFloat()
        val expected = if (tiles.isEmpty()) 0f else 1f
        assertEquals("tiles cover $covered of the box", expected, covered, TOLERANCE)
    }

    private fun overlap(a: MosaicRect, b: MosaicRect): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun covers(count: Int): List<String> = (0 until count).map { "cover-$it" }

    private var nextId = 1L

    private fun song(album: String, artist: String = "Artist"): Song = nextId++.let { id ->
        Song(
            id = id,
            sourceId = "test-source",
            externalId = "ms-${id + 9000}",
            uri = "content://media/external/audio/media/$id",
            filePath = "/x/Music/$id.mp3",
            relativeKey = "external_primary:Music/$id.mp3",
            title = "Title $id",
            artist = artist,
            album = album,
            albumArtist = null,
            trackNumber = null,
            discNumber = null,
            year = null,
            durationMs = 1_000L,
            dateModifiedSec = 0L,
            hasEmbeddedArt = false,
        )
    }

    private fun resolved(song: Song): PlaylistTrack = PlaylistTrack(
        entry = PlaylistEntryEntity(
            id = song.id,
            playlistId = 7L,
            position = song.id.toInt(),
            sourceId = "test-source",
            sourceKey = "key-${song.id}",
            songId = song.id,
            sourceTitle = song.title,
            sourceArtist = song.artist,
            sourceAlbum = song.album,
        ),
        song = song,
    )

    private companion object {
        const val TOLERANCE = 1e-5f
    }
}
