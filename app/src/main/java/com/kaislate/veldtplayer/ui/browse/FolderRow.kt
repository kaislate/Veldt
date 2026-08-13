// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.library.FolderNode
import com.kaislate.veldtplayer.data.library.FolderSort
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The leading slot, matching `SongRow`'s `LEADING_SIZE` so folders and tracks share one rhythm. */
private val LEADING_SIZE = 48.dp

/**
 * How many distinct albums a folder's mosaic can use. [mosaicTiles] draws at most four, so
 * collecting past four is work with nothing to show for it.
 */
private const val MOSAIC_ALBUMS = 4

/**
 * One directory.
 *
 * **[label] is passed in rather than read off [node].** A top-level row is named after its VOLUME —
 * two volumes routinely elide to two folders both called `Music` — and that decision belongs to
 * [FolderViewModel.listing], which is where it is tested. See [FolderRowItem].
 *
 * **Not shown, deliberately:** file size and bitrate (not indexed, and file-manager concerns), the
 * full path (the breadcrumb carries it), and the modification date (available, but noisy at row
 * level — it is a *sort*, not a caption). No per-row palette theming and no full-bleed header
 * either: a folder is a place you pass through, often four in a row.
 */
@Composable
fun FolderRow(
    node: FolderNode,
    label: String,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // remember(node): the mosaic walk is over every descendant, so without this it would run on
    // every recomposition of every visible row. The node is rebuilt per tree emission, which is
    // exactly when the covers can have changed.
    val covers = remember(node) { folderCovers(node) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistMosaic(
            covers = covers,
            palette = palette,
            initial = label.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .size(LEADING_SIZE)
                .clip(RoundedCornerShape(8.dp)),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                // VERBATIM — no case folding, no prettifying. It is a path.
                text = label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = folderCaption(node),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `3 folders · 42 tracks · 2h 51m`, from the DEEP aggregates.
 *
 * Deep and not direct, because a parent of six album folders holds no tracks of its own and a row
 * reading "0 tracks" over 300 of them is worse than no caption at all.
 *
 * The folder clause is omitted at zero — a leaf reads `12 tracks · 48m`. The duration clause is
 * omitted when the aggregate is zero, which is what a library of tracks MediaStore reported no
 * duration for produces; `· 0m` there would be a claim rather than a fact.
 */
internal fun folderCaption(node: FolderNode): String = buildList {
    if (node.deepFolderCount > 0) add(countOf(node.deepFolderCount, "folder"))
    add(countOf(node.deepSongCount, "track"))
    if (node.deepDurationMs > 0L) add(folderDuration(node.deepDurationMs))
}.joinToString(" · ")

/**
 * `2h 51m`, or `51m` under an hour — never `2:51:00`.
 *
 * A different shape from `formatTime`, on purpose: that one labels a playback position, where
 * seconds are the unit the user is reading. This one is a size, where they are noise. Rounds DOWN,
 * so a caption never claims more music than the folder holds.
 */
internal fun folderDuration(ms: Long): String {
    val minutes = ms / 60_000L
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

/**
 * The covers a folder's mosaic is built from: one per distinct album key among its DESCENDANTS.
 *
 * Deep, not direct — a parent of six album folders would otherwise be blank, which is the case that
 * makes the mosaic worth having at all.
 *
 * **Deterministic, and here is exactly why.** [coverTrack] is order-independent, so *which track*
 * stands for an album does not depend on the walk. That alone does not make the mosaic stable —
 * *which albums* are drawn also has to be fixed, and this walk fixes it: direct songs first, then
 * children in [FolderSort.folders] order, which is [FolderSort.NATURAL] by name and so independent
 * of the user's track sort and of the order Room returned rows in. The cap is a consequence of that
 * order rather than of arrival: once [MOSAIC_ALBUMS] distinct albums are collected the walk stops,
 * and it stops at the same place every time.
 *
 * A capped album's group holds only the share the walk reached, so [coverTrack] chooses over a
 * subset. That is deterministic but it is *not* the same choice the album's own detail screen
 * makes — folder rows are not an end of any shared-element morph, so no cache entry depends on it.
 */
internal fun folderCovers(node: FolderNode): List<Song> {
    val byAlbum = LinkedHashMap<String, MutableList<Song>>()
    collectCovers(node, byAlbum)
    return byAlbum.values.mapNotNull { it.coverTrack() }
}

private fun collectCovers(node: FolderNode, out: LinkedHashMap<String, MutableList<Song>>) {
    for (song in node.songs) {
        out.getOrPut(LibraryKeys.albumKey(song)) { ArrayList() } += song
    }
    for (child in FolderSort.folders(node.children)) {
        if (out.size >= MOSAIC_ALBUMS) return
        collectCovers(child, out)
    }
}
