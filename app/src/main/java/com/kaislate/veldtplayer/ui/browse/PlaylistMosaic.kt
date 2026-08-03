// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.ArtPlaceholder
import com.kaislate.veldtplayer.ui.theme.DominantColors
import kotlin.math.roundToInt

/**
 * The artwork a playlist is known by: a mosaic of the records it is made of (spec §3.3).
 *
 * A playlist has no cover of its own, and the usual answer to that is a list glyph — the same
 * grey note icon on every row, which tells a user nothing and makes the tab indistinguishable
 * from every other player's. Here a playlist earns a colour identity the way an album does: out
 * of its own records' covers.
 *
 * **Everything this file decides is decided in [mosaicTiles], including the geometry.** How many
 * covers a mosaic uses and where each one goes are one question, not two — a slot count that
 * disagreed with the layout it was drawn under is exactly the sort of defect that leaves a hole
 * in a corner while every count assertion stays green. The composable below reads the answer and
 * places pixels; it makes no choice of its own.
 */

// -------------------------------------------------------------------------------- the geometry

/**
 * One tile's share of the mosaic box, in fractions of its width and height.
 *
 * Fractions rather than `Dp`, because the SAME layout has to hold at 60dp on the playlists tab
 * and at a 300dp full-bleed header on the detail screen, and because a fraction is a thing a JVM
 * test can assert. `left`/`right` are leading/trailing rather than absolute — see the
 * `placeRelative` in [PlaylistMosaic].
 */
data class MosaicRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width * height
}

/** One cover and the part of the box it fills. */
data class MosaicTile<T>(val cover: T, val rect: MosaicRect)

/** The whole box. What a lone cover gets — see [mosaicTiles]. */
private val FULL = MosaicRect(0f, 0f, 1f, 1f)

/**
 * A leading half at full height, then two trailing quarters stacked behind it.
 *
 * Not a 2x2 with a hole in it, and not three equal columns — three narrow strips of album art
 * are three unreadable slivers. The record the playlist OPENS with takes the big half, which is
 * both the most likely one to be recognised and a composition rather than a grid.
 */
private val THREE_UP = listOf(
    MosaicRect(0f, 0f, 0.5f, 1f),
    MosaicRect(0.5f, 0f, 1f, 0.5f),
    MosaicRect(0.5f, 0.5f, 1f, 1f),
)

/** Four quadrants, in reading order. */
private val FOUR_UP = listOf(
    MosaicRect(0f, 0f, 0.5f, 0.5f),
    MosaicRect(0.5f, 0f, 1f, 0.5f),
    MosaicRect(0f, 0.5f, 0.5f, 1f),
    MosaicRect(0.5f, 0.5f, 1f, 1f),
)

// ------------------------------------------------------------------------------- the decision

/**
 * Which covers the mosaic draws, in playlist order: **0, 1, 3 or 4 of them, never 2.**
 *
 * Two covers is the case with no honest layout. A 2x2 grid holding two of them is a tile with a
 * hole punched in one corner; two half-width strips make a mosaic out of a playlist that only
 * has one record's worth of identity to show. So two covers draw the FIRST one, full bleed —
 * the same thing an album tile does, which is the honest answer to "this playlist is mostly one
 * record".
 *
 * **It does not de-duplicate.** "Are these two tracks the same record" is a question about the
 * library, answered by `LibraryKeys.albumKey` inside [PlaylistPresentation.coversOf], and both
 * call sites hand this the list that function returns. Asking it a second time here — on some
 * weaker notion of identity, since a `Song`'s art source is unique per song and could never
 * collapse two tracks off one record — would do nothing except hide an upstream regression:
 * `coversOf` would be broken for every other consumer while the mosaic went on looking right.
 * `PlaylistMosaicTest` therefore asserts the collapse through the real pipeline, and the
 * negative control that proves it removes the `distinctBy` from `coversOf`.
 *
 * Generic so that the tests can pin the rule on plain strings while the composable runs the very
 * same body on `Song`s. One function, one decision, both surfaces.
 */
fun <T> mosaicSlots(covers: List<T>): List<T> = covers.take(slotCountFor(covers.size))

/**
 * The covers the mosaic draws AND where each one goes.
 *
 * Derived from [mosaicSlots] rather than deciding a count of its own, so the two can never
 * disagree about how many tiles there are.
 */
fun <T> mosaicTiles(covers: List<T>): List<MosaicTile<T>> {
    val chosen = mosaicSlots(covers)
    return chosen.zip(rectsFor(chosen.size)) { cover, rect -> MosaicTile(cover, rect) }
}

/** 0 -> nothing, 1 or 2 -> one, 3 -> three, 4 or more -> four. */
private fun slotCountFor(coverCount: Int): Int = when {
    coverCount <= 0 -> 0
    coverCount < 3 -> 1
    coverCount == 3 -> 3
    else -> 4
}

/**
 * The rectangles for [slotCount] tiles. They tile the box EXACTLY — no gap, no overlap — which
 * is what "never 2-with-a-hole" means once it is a claim about pixels rather than about a count.
 */
private fun rectsFor(slotCount: Int): List<MosaicRect> = when (slotCount) {
    0 -> emptyList()
    1 -> listOf(FULL)
    3 -> THREE_UP
    4 -> FOUR_UP
    // Unreachable via slotCountFor, and deliberately NOT a silent full-bleed: a count with no
    // layout would drop tiles in the zip above, which `mosaicTiles agrees with mosaicSlots about
    // how many tiles there are` catches for every size.
    else -> emptyList()
}

/**
 * The letter a tile falls back to when its artwork will not load.
 *
 * The ALBUM's initial, not the playlist's: four tiles all showing the same letter is the generic
 * glyph again, drawn four times. [fallback] — the playlist's own initial — is used only for a
 * record with no album tag at all, where there is nothing better to say.
 */
internal fun tileInitial(song: Song, fallback: Char): Char =
    DisplayNames.tagOrNull(song.album)?.firstOrNull { it.isLetterOrDigit() } ?: fallback

// ------------------------------------------------------------------------------ the composable

/**
 * The mosaic itself. Consumed through `PlaylistCoverArt`, so the playlists tab's stacked emblem
 * and the detail screen's full-bleed header are the same artwork at two sizes.
 *
 * A custom [Layout] rather than nested rows and columns: the tiles are fractions of whatever box
 * this lands in, and rounding each EDGE to a pixel — instead of rounding each tile's width — is
 * what makes neighbouring tiles butt together with no hairline of background showing between
 * them at 60dp.
 */
@Composable
fun PlaylistMosaic(
    covers: List<Song>,
    palette: DominantColors,
    initial: Char,
    modifier: Modifier = Modifier,
) {
    val tiles = remember(covers) { mosaicTiles(covers) }
    if (tiles.isEmpty()) {
        // No resolved track has artwork to offer — an empty playlist, or one whose every entry
        // is missing. The palette wash and the playlist's own initial, exactly as an art-less
        // album tile draws itself.
        ArtPlaceholder(initial = initial, palette = palette, modifier = modifier)
        return
    }
    Layout(
        modifier = modifier,
        content = {
            tiles.forEach { tile ->
                ArtImage(
                    art = tile.cover.toSongArt(),
                    palette = palette,
                    initial = tileInitial(tile.cover, initial),
                )
            }
        },
    ) { measurables, constraints ->
        // An unbounded axis would make a fraction meaningless; both call sites are bounded (a
        // fixed 60dp box on the tab, fillMaxSize inside a fixed-height header on the detail
        // screen), and an unbounded one degrades to its minimum rather than throwing.
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
        val placeables = measurables.mapIndexed { index, measurable ->
            val rect = tiles[index].rect
            measurable.measure(
                Constraints.fixed(
                    width = edge(rect.right, width) - edge(rect.left, width),
                    height = edge(rect.bottom, height) - edge(rect.top, height),
                ),
            )
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val rect = tiles[index].rect
                // placeRelative, so the leading tile of the three-up leads in RTL too. The
                // mosaic is a composition with a subject, not a grid of interchangeable cells.
                placeable.placeRelative(edge(rect.left, width), edge(rect.top, height))
            }
        }
    }
}

/** A fractional edge, snapped to a whole pixel. Both tiles either side of it snap identically. */
private fun edge(fraction: Float, extent: Int): Int =
    (fraction * extent).roundToInt().coerceIn(0, extent.coerceAtLeast(0))
