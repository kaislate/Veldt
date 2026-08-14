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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import com.kaislate.veldtplayer.data.library.FolderNode
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
    playlistVm: PlaylistViewModel,
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

    // A folder, a subtree, or one track — see PlaylistAdditions for why those are different
    // subjects rather than one list with a different length.
    var pendingAddition by remember { mutableStateOf<PlaylistAddition?>(null) }
    AddToPlaylistHost(
        vm = playlistVm,
        addition = pendingAddition,
        onDismiss = { pendingAddition = null },
    )

    // Which folder row's long-press menu is open, held as its KEY rather than as the row itself.
    // A tree emission rebuilds every FolderRowItem, so a remembered item would be a stale object
    // the moment a scan lands — and a menu anchored to a row that no longer exists simply does not
    // recompose, which is the correct outcome and the reason this is a key.
    var openMenuKey by remember { mutableStateOf<String?>(null) }

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
        // about to tap, and a transient empty emission mid-scan would do it spuriously.
        //
        // **The `scanning` split is the whole behaviour, not a nicety.** This branch is NOT guarded
        // by `roots.isEmpty()`, so a `scanning` flag stuck true leaves an unresolvable key under
        // the spinner forever on a library that is working fine — no Go back, no way out. Both
        // halves of the pair this reads are pinned in `FolderViewModelTest`: see
        // `a folder that vanishes mid-scan is not reported missing yet` and
        // `a dead key over a settled library reports the folder unavailable`.
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
                        // The header hands its own non-null node back rather than the screen
                        // re-reading `listing.node` inside a click handler, which would be a second
                        // null check of a value the header has already resolved.
                        onVerb = { node, verb, scope ->
                            runFolderVerb(vm, state, node, listing.subject, verb, scope) {
                                pendingAddition = it
                            }
                        },
                        onSort = vm::setSort,
                        onDescending = vm::setDescending,
                    )
                }
                // Keyed on FolderNode.key and song.id. The tree is rebuilt object-by-object on
                // every emission, so without stable keys a live rescan re-derives the whole list
                // and scrolls the user back to the top mid-scan.
                //
                // animateItem(): live-refresh behaviour 1. A folder that gains tracks under an open
                // screen slides them in against the rows already there. Without it the list jumps,
                // and the jump is indistinguishable from the user having mis-tapped.
                items(listing.folders, key = { it.node.key }) { row ->
                    Box(Modifier.animateItem()) {
                        FolderRow(
                            item = row,
                            palette = palette,
                            onClick = { onOpenFolder(row.node.key) },
                            // The interaction that makes a deep tree tolerable: every verb the
                            // header offers, without entering the folder first.
                            onLongClick = { openMenuKey = row.node.key },
                        )
                        FolderVerbMenu(
                            expanded = openMenuKey == row.node.key,
                            node = row.node,
                            includePlay = true,
                            onDismiss = { openMenuKey = null },
                            onVerb = { verb, scope ->
                                runFolderVerb(vm, state, row.node, row.label, verb, scope) {
                                    pendingAddition = it
                                }
                            },
                        )
                    }
                }
                itemsIndexed(listing.tracks, key = { _, song -> song.id }) { index, song ->
                    // SongRow verbatim, as AlbumDetailScreen and SongsScreen use it — that is what
                    // keeps art size, truncation and touch target identical across the app.
                    SongRow(
                        song = song,
                        palette = palette,
                        onClick = { vm.play(listing.tracks, index) },
                        modifier = Modifier.animateItem(),
                        // Folder tracks were the one list in the app with no route to a playlist.
                        onLongClick = { pendingAddition = PlaylistAdditions.ofSong(song) },
                    )
                }
            }
        }
    }
}

/**
 * Breadcrumb, one stats line, and the verbs — compact, because it is above every folder.
 *
 * **The primary button is the DEEP one** ([FolderScope.WITH_SUBFOLDERS]) — owner decision,
 * 2026-08-13. It is drawn whenever the subtree holds anything, which is always: a node exists only
 * because a song mapped into it or into a descendant. The direct-only verbs live in the overflow
 * and are drawn only where the two scopes differ; see [FolderVerbMenu].
 *
 * The old rule — play drawn only when `listing.tracks` is non-empty — is gone with the shallow
 * primary it belonged to. Under it, a parent of six album folders offered no play button at all,
 * which is precisely the folder a user opens meaning "play the record".
 *
 * The synthetic volume-chooser node is the one place with no verbs: [FolderScope] names a folder,
 * and "play every volume on the device, depth-first" is the Songs tab under a different name. Its
 * subject would be the word "Folders", which names nothing a playlist should be captioned with.
 */
@Composable
private fun FolderHeader(
    listing: FolderListing,
    sort: TrackSort,
    descending: Boolean,
    onCrumb: (String) -> Unit,
    onVerb: (FolderNode, FolderVerb, FolderScope) -> Unit,
    onSort: (TrackSort) -> Unit,
    onDescending: (Boolean) -> Unit,
) {
    val node = listing.node ?: return
    var overflow by remember { mutableStateOf(false) }
    val verbs = node.key != DEVICE_KEY
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
                if (verbs) {
                    IconButton(
                        onClick = { onVerb(node, FolderVerb.PLAY, FolderScope.WITH_SUBFOLDERS) },
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            // Says which scope, because the folder in front of the user may hold
                            // no tracks of its own and the button still plays 300 of them.
                            contentDescription = "Play this folder and its subfolders",
                        )
                    }
                    IconButton(
                        onClick = { onVerb(node, FolderVerb.SHUFFLE, FolderScope.WITH_SUBFOLDERS) },
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = "Shuffle this folder and its subfolders",
                        )
                    }
                    Box {
                        IconButton(onClick = { overflow = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More folder actions")
                        }
                        FolderVerbMenu(
                            expanded = overflow,
                            node = node,
                            // The header already carries play and shuffle as buttons; repeating
                            // them one tap deeper is a menu item that competes with itself.
                            includePlay = false,
                            onDismiss = { overflow = false },
                            onVerb = { verb, scope -> onVerb(node, verb, scope) },
                        )
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

/** What a folder menu can do. The SCOPE is the other half — see [FolderScope]. */
internal enum class FolderVerb { PLAY, SHUFFLE, QUEUE, PLAYLIST }

/**
 * The one place a (verb, scope) pair becomes a view-model call.
 *
 * Two menus reach it — the header's overflow and a row's long press — and they must offer the same
 * verbs, because the whole point of the long press is that the user need not enter a folder to act
 * on it. Two `when` blocks would be two chances for one of them to lose the shallow scope.
 *
 * Not a composable and not on the view model: the playlist half ends in a bottom sheet, which is
 * screen state, and a view model that took a `(PlaylistAddition) -> Unit` to hand one back would be
 * a view model holding a composable's `remember`.
 */
private fun runFolderVerb(
    vm: FolderViewModel,
    state: FolderUiState,
    node: FolderNode,
    subject: String,
    verb: FolderVerb,
    scope: FolderScope,
    onAddition: (PlaylistAddition) -> Unit,
) = when (verb) {
    FolderVerb.PLAY -> when (scope) {
        FolderScope.WITH_SUBFOLDERS -> vm.playFolderDeep(state, node)
        FolderScope.THIS_FOLDER -> vm.playFolderShallow(state, node)
    }
    FolderVerb.SHUFFLE -> vm.shuffleFolder(state, node, scope)
    FolderVerb.QUEUE -> vm.addFolderToQueue(state, node, scope)
    FolderVerb.PLAYLIST -> onAddition(vm.folderAddition(state, node, subject, scope))
}

/**
 * Every verb a folder offers, at both scopes — the header's overflow and a row's long press.
 *
 * **A direct-only item is drawn only where it would differ from the deep one.** A leaf folder's two
 * scopes are the same list, so offering both charges the user a decision between two identical
 * outcomes; and a folder with subfolders but no tracks of its own has an EMPTY direct list, so a
 * "this folder only" item there is an entry that does nothing. Both conditions are reads of the
 * node the menu was opened on — no walk, no derivation, and nothing per recomposition.
 *
 * [includePlay] is false for the header, which already draws play and shuffle as buttons.
 */
@Composable
private fun FolderVerbMenu(
    expanded: Boolean,
    node: FolderNode,
    includePlay: Boolean,
    onDismiss: () -> Unit,
    onVerb: (FolderVerb, FolderScope) -> Unit,
) {
    // The subtree is strictly larger than the direct list exactly when both are non-trivial.
    val shallowDiffers = node.children.isNotEmpty() && node.songs.isNotEmpty()
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (includePlay) {
            FolderVerbItem("Play", FolderVerb.PLAY, FolderScope.WITH_SUBFOLDERS, onDismiss, onVerb)
            FolderVerbItem(
                "Shuffle", FolderVerb.SHUFFLE, FolderScope.WITH_SUBFOLDERS, onDismiss, onVerb,
            )
        }
        FolderVerbItem(
            "Add to queue", FolderVerb.QUEUE, FolderScope.WITH_SUBFOLDERS, onDismiss, onVerb,
        )
        // "Add to playlist", not "add folder" — the entries are a SNAPSHOT and the wording is the
        // only thing that says so. See PlaylistAdditions.ofFolder.
        FolderVerbItem(
            "Add to playlist", FolderVerb.PLAYLIST, FolderScope.WITH_SUBFOLDERS, onDismiss, onVerb,
        )
        if (shallowDiffers) {
            HorizontalDivider()
            // Drawn for BOTH menus, including the header's — this is the brief's "play this folder
            // only", which is the header's secondary action and has no button of its own.
            FolderVerbItem(
                "Play this folder only", FolderVerb.PLAY, FolderScope.THIS_FOLDER,
                onDismiss, onVerb,
            )
            FolderVerbItem(
                "Queue this folder only", FolderVerb.QUEUE, FolderScope.THIS_FOLDER,
                onDismiss, onVerb,
            )
            FolderVerbItem(
                "Add this folder only to playlist", FolderVerb.PLAYLIST, FolderScope.THIS_FOLDER,
                onDismiss, onVerb,
            )
        }
    }
}

@Composable
private fun FolderVerbItem(
    label: String,
    verb: FolderVerb,
    scope: FolderScope,
    onDismiss: () -> Unit,
    onVerb: (FolderVerb, FolderScope) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            // Dismissed FIRST: the playlist verb opens a bottom sheet, and a menu still expanded
            // behind a modal sheet is a second dismissible surface the user has to notice.
            onDismiss()
            onVerb(verb, scope)
        },
    )
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
