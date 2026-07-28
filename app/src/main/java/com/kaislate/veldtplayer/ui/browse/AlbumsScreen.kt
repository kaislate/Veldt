// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.motion.albumArtKey
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/**
 * Narrowest a tile may get before the grid drops a column: two up on a phone, three or
 * four on a tablet. Adaptive rather than a fixed column count so the artwork keeps a
 * sensible physical size instead of ballooning with the window.
 */
private val TILE_MIN_WIDTH = 168.dp

/** Gutter between tiles, and the grid's own side margin. */
private val GRID_GUTTER = 12.dp
private val GRID_MARGIN = 16.dp

/** The tile's touch/ripple shape; the cover is clipped slightly tighter inside it. */
private val TILE_SHAPE = RoundedCornerShape(18.dp)
private val COVER_SHAPE = RoundedCornerShape(14.dp)

/** Gap between the cover and its caption — enough that the text reads as a label, not a bar. */
private val CAPTION_GAP = 10.dp

/**
 * Art-dominant grid: the cover IS the item and the text is its caption. Covers bleed to
 * the tile edge with no frame, no elevation and no card behind them — anything drawn
 * around the artwork competes with it.
 */
@Composable
fun AlbumsScreen(
    vm: BrowseViewModel,
    onOpenAlbum: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, not collectAsState: every VM flow is WhileSubscribed
    // and a backgrounded tab must let the upstream stop.
    val albums by vm.albums.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    // The neutral fallback palette, as in the songs list: a grid themed by 60 different
    // covers at once would be noise. Per-artwork colour is a now-playing concern.
    val palette = ColorExtractor.extract(null)

    if (albums.isEmpty()) {
        // Three states, not two — the same distinction SongsScreen draws. On a fresh
        // install the scan is enqueued and Room reports an empty library in the same
        // breath, so "No albums yet" plus a Scan button that KEEP would no-op is a lie
        // for the whole length of the first scan.
        if (scanning) {
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)
        } else {
            EmptyState(
                palette = palette,
                title = "No albums yet",
                body = "Albums appear once Veldt has scanned music with album tags.",
                actionLabel = "Scan library",
                onAction = vm::scan,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        return
    }

    // One representative track per album supplies the cover.
    //
    // Keyed on the COMPOUND album key, never on the album title: Queen's and ABBA's
    // "Greatest Hits" are two tiles, and a title-keyed map would hand them one cover.
    // The track itself is chosen by coverTrack(), which is order-independent — the album
    // page reaches the same decision from a differently-sorted list, which is what lets
    // the two ends of the art morph share one decoded bitmap.
    val coverByKey: Map<String, Song?> = remember(songs) {
        songs.groupBy { LibraryKeys.albumKey(it) }
            .mapValues { (_, rows) -> rows.coverTrack() }
    }

    // Insets are split exactly as in SongsScreen: the bottom one becomes contentPadding so
    // tiles scroll beneath the translucent navigation bar, while the top/side ones stay a
    // padding modifier because nothing is drawn over the status bar to scrim them.
    val direction = LocalLayoutDirection.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = TILE_MIN_WIDTH),
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            ),
        contentPadding = PaddingValues(
            start = GRID_MARGIN,
            end = GRID_MARGIN,
            top = LIST_AIR,
            bottom = contentPadding.calculateBottomPadding() + GRID_MARGIN,
        ),
        horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER),
        verticalArrangement = Arrangement.spacedBy(GRID_GUTTER),
    ) {
        itemsIndexed(albums, key = { _, album -> album.key }) { index, album ->
            AlbumTile(
                album = album,
                cover = coverByKey[album.key],
                palette = palette,
                onClick = { onOpenAlbum(album.key) },
                modifier = Modifier.staggeredEntrance(index, reduced),
            )
        }
    }
}

/** One square cover with a two-line caption. */
@Composable
private fun AlbumTile(
    album: Album,
    cover: Song?,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = DisplayNames.album(album.name)
    // The album artist when the tags carry one, otherwise the representative track's
    // artist — which is exactly the field LibraryKeys.albumKey grouped on, so the caption
    // always names the same owner the tile was keyed by. DisplayNames owns that fallback
    // (and the "<unknown>" sentinel behind it) for every screen at once.
    val artist = DisplayNames.albumArtist(album.albumArtist, cover?.artist)

    Column(
        modifier = modifier
            // Clip BEFORE clickable so the ripple stops at the tile's corners instead of
            // painting a hard rectangle over the rounded artwork.
            .clip(TILE_SHAPE)
            .clickable(onClick = onClick)
            .padding(bottom = CAPTION_GAP),
    ) {
        // aspectRatio, not an unbounded height: ArtImage's loading state fills its parent,
        // so under unbounded constraints the tile would collapse and jump when art lands.
        //
        // sharedArt makes this cover the SOURCE of the morph into the album page: tapping
        // the tile sends this artwork to the detail header instead of cross-fading two
        // copies of it.
        //
        // It sits BEFORE clip() so the clip is part of what travels — the radius itself is
        // a constant and does not interpolate, but a clip applied outside the shared node
        // would be left behind when the element renders into the transition overlay, and
        // the cover would square off for the length of the morph.
        ArtImage(
            art = cover?.toSongArt(),
            palette = palette,
            initial = title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArt(albumArtKey(album.key))
                .clip(COVER_SHAPE),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = CAPTION_GAP),
        )
        Text(
            text = artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
