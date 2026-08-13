// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.ui.components.ArtPlaceholder
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.theme.neutralPalette

/**
 * One folder — and the tab root, which is the same screen with a different node in it.
 *
 * [folderKey] is null for the tab root; anything else is the byte-exact key carried by
 * `Destinations.folder`. One nav destination PER folder, so back pops one level: predictive back
 * runs, the framework restores the stack across process death, and the tab's `saveState` /
 * `restoreState` keeps the folder stack for free. A `BackHandler` over an in-state path would give
 * up all three.
 *
 * **No 300 dp parallax header.** `AlbumDetailScreen` earns one because an album IS its cover; a
 * folder is a place you pass through, often four in a row, and a header that costs half a screen
 * each time is a header that gets scrolled past four times.
 */
@Composable
fun FolderScreen(
    vm: FolderViewModel,
    folderKey: String?,
    onOpenFolder: (String) -> Unit,
    navController: NavController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val palette = neutralPalette()

    // Derived ONCE per (state, key) pair — global constraint 14's collector half. The FolderTree
    // walk happens inside the view model; what this remember buys is that it does not happen again
    // on a recomposition that changed neither the tree nor the folder being looked at.
    val listing = remember(state, folderKey) { vm.listing(state, folderKey) }

    when {
        // The same three-way distinction every other browse surface draws. Claiming "no folders"
        // mid-scan is the lie P1.3 told about the library, and a restored back stack can land on a
        // folder route before the library has been read at all.
        state.roots.isEmpty() && state.scanning ->
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)

        state.roots.isEmpty() -> EmptyState(
            palette = palette,
            title = "No folders yet",
            body = "Veldt shows folders containing indexed music, podcasts and audiobooks. " +
                "Ringtones, alarms and notification sounds are not included.",
            actionLabel = "Scan library",
            onAction = vm::scan,
            contentPadding = contentPadding,
            modifier = modifier,
        )

        // Songs exist and not one of them has a location. Rendering the tab as a blank list would
        // blame the user's music for a media-index problem — and the Scan button that "No folders
        // yet" offers cannot fix it, so this surface deliberately offers none.
        state.locationsUnavailable -> BrowseMessage(
            palette = palette,
            title = "No file locations",
            body = "Folder browsing is unavailable because the media index reported no file " +
                "locations for your music. Every other tab still works.",
            contentPadding = contentPadding,
            modifier = modifier,
            emblem = {
                ArtPlaceholder(initial = '♪', palette = palette, modifier = Modifier.fillMaxSize())
            },
        )

        // The folder went away while its screen was open — deleted, or the card unmounted. It is
        // NOT auto-popped: a screen that vanishes under the user's thumb loses whatever they were
        // about to tap. See Task 6.
        listing.node == null -> if (state.scanning) {
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)
        } else {
            EmptyState(
                palette = palette,
                title = "Folder unavailable",
                body = "This folder is no longer in the library. It may have been deleted, " +
                    "renamed, or moved off the device.",
                actionLabel = "Go back",
                onAction = { navController.popBackStack() },
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }

        else -> {
            val direction = LocalLayoutDirection.current
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(
                        start = contentPadding.calculateStartPadding(direction),
                        top = contentPadding.calculateTopPadding(),
                        end = contentPadding.calculateEndPadding(direction),
                    ),
                contentPadding = PaddingValues(
                    top = LIST_AIR,
                    bottom = contentPadding.calculateBottomPadding() + LIST_AIR,
                ),
            ) {
                item(key = "header") {
                    FolderHeader(
                        listing = listing,
                        sort = state.sort,
                        descending = state.descending,
                        onCrumb = { route -> navController.openCrumb(route) },
                        onPlay = { vm.play(listing.tracks, 0) },
                        onShuffle = { vm.shuffle(listing.tracks) },
                        onSort = vm::setSort,
                        onDescending = vm::setDescending,
                    )
                }
                // Keyed on FolderNode.key and song.id. The tree is rebuilt object-by-object on
                // every emission, so without stable keys a live rescan re-derives the whole list
                // and scrolls the user back to the top mid-scan.
                items(listing.folders, key = { it.node.key }) { row ->
                    FolderRow(
                        node = row.node,
                        label = row.label,
                        palette = palette,
                        onClick = { onOpenFolder(row.node.key) },
                    )
                }
                itemsIndexed(listing.tracks, key = { _, song -> song.id }) { index, song ->
                    // SongRow verbatim, as AlbumDetailScreen and SongsScreen use it — that is what
                    // keeps art size, truncation and touch target identical across the app.
                    SongRow(
                        song = song,
                        palette = palette,
                        onClick = { vm.play(listing.tracks, index) },
                    )
                }
            }
        }
    }
}

/**
 * Breadcrumb, one stats line, and the three verbs — compact, because it is above every folder.
 *
 * Play and shuffle are drawn only when the folder holds tracks of its own. The queue is the DIRECT
 * list, so in a folder that only holds subfolders they would be buttons that do nothing; see
 * [FolderViewModel.play].
 */
@Composable
private fun FolderHeader(
    listing: FolderListing,
    sort: TrackSort,
    descending: Boolean,
    onCrumb: (String) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSort: (TrackSort) -> Unit,
    onDescending: (Boolean) -> Unit,
) {
    val node = listing.node ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN)
            .padding(bottom = 8.dp),
    ) {
        FolderBreadcrumb(crumbs = listing.crumbs, onCrumb = onCrumb)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = folderCaption(node),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                if (listing.tracks.isNotEmpty()) {
                    IconButton(onClick = onPlay) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play this folder")
                    }
                    IconButton(onClick = onShuffle) {
                        Icon(Icons.Filled.Shuffle, contentDescription = "Shuffle this folder")
                    }
                }
                SortMenu(
                    sort = sort,
                    descending = descending,
                    onSort = onSort,
                    onDescending = onDescending,
                )
            }
        }
    }
}

/**
 * The track order, and its direction.
 *
 * One menu holding both, because they are one question — "how am I reading this folder?" — and a
 * separate direction toggle beside it would be a second control the user has to notice. The
 * preference is app-wide and persisted (`SettingsRepository.folderSort`), so it is not re-chosen on
 * every folder.
 */
@Composable
private fun SortMenu(
    sort: TrackSort,
    descending: Boolean,
    onSort: (TrackSort) -> Unit,
    onDescending: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort tracks")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TrackSort.entries.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(sortLabel(entry)) },
                    trailingIcon = {
                        if (entry == sort) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        open = false
                        onSort(entry)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Reverse order") },
                trailingIcon = {
                    if (descending) Icon(Icons.Filled.Check, contentDescription = null)
                },
                onClick = {
                    open = false
                    onDescending(!descending)
                },
            )
        }
    }
}

/** Filename first, because it is the default and the reason this view exists. See `FolderSort`. */
private fun sortLabel(sort: TrackSort): String = when (sort) {
    TrackSort.FILENAME -> "File name"
    TrackSort.TRACK_NUMBER -> "Track number"
    TrackSort.TITLE -> "Title"
    TrackSort.DATE_MODIFIED -> "Date modified"
}
