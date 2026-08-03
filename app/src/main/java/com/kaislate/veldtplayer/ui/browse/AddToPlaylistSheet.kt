// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The artwork of what is being added, at the head of the sheet. */
private val SUBJECT_SIZE = 56.dp

/** How tall the picker may grow before it scrolls rather than pushing its own header away. */
private val LIST_MAX_HEIGHT = 380.dp

/**
 * Where a song, a record or an artist goes when the user wants it in a playlist (spec §3.2).
 *
 * **Not a list of names.** A picker sheet is the surface every other player renders as plain text
 * rows, and it is the one moment where the user is looking at their playlists as a SET — which is
 * exactly when the artwork is worth the space. So each row carries the tab's own stacked emblem,
 * with the same mosaic front tile, and the sheet opens under the cover of the thing being added, so
 * the user can see at a glance that they are filing the record they meant to.
 *
 * The whole thing is driven by [PlaylistViewModel.state] — the same flow the tab reads — so a
 * playlist created here appears in the tab without a refresh, and the three states that flow
 * distinguishes stay distinguished: a library still loading shows a spinner, not "no playlists".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(
    vm: PlaylistViewModel,
    addition: PlaylistAddition,
    onDismiss: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState()
    val palette = ColorExtractor.extract(null)
    var naming by remember { mutableStateOf(false) }

    // The names already taken, read off the SAME state the rows are drawn from, so the suggested
    // name cannot collide with a playlist the user can see in this very sheet.
    val cards = (state as? PlaylistsUiState.Ready)?.cards.orEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            SheetHeader(addition = addition, palette = palette)

            LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                item(key = "new") {
                    PickerRow(
                        title = "New playlist…",
                        subtitle = "Create one and file these tracks in it",
                        onClick = { naming = true },
                        emblem = { NewPlaylistStack(palette = palette) },
                    )
                }
                when (state) {
                    // A spinner, not an empty list: resolving every playlist against the library
                    // is not instant, and drawing "New playlist…" alone over a set that is still
                    // arriving would invite the user to create a duplicate of one they own.
                    PlaylistsUiState.Loading -> item(key = "loading") { PickerLoading() }

                    // Genuinely none. No message is needed — "New playlist…" above IS the answer,
                    // and a "you have no playlists" line under an affordance that makes one reads
                    // as an error about something the user is already fixing.
                    PlaylistsUiState.Empty -> Unit

                    is PlaylistsUiState.Ready -> items(cards, key = { it.id }) { card ->
                        PickerRow(
                            title = card.name,
                            subtitle = PlaylistPresentation.caption(
                                card.trackCount,
                                card.missingCount,
                            ),
                            onClick = {
                                vm.addTo(card.id, card.name, addition)
                                onDismiss()
                            },
                            emblem = { PlaylistStack(card = card, palette = palette) },
                        )
                    }
                }
            }
        }
    }

    if (naming) {
        NameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            initial = PlaylistNaming.suggestedName(cards.map { it.name }),
            onDismiss = { naming = false },
            onConfirm = { name ->
                // Created AND filled in one call — see PlaylistViewModel.create. A create that
                // returned before the add is the empty-playlist dead end Task 6 refused to ship.
                vm.create(name, addition)
                naming = false
                onDismiss()
            },
        )
    }
}

/**
 * What is being added, said and shown.
 *
 * The cover is the FIRST track's, which for a record or an artist page is the one already filling
 * the screen behind the sheet — so the sheet reads as an extension of what was tapped rather than
 * as a modal that arrived from nowhere.
 */
@Composable
private fun SheetHeader(addition: PlaylistAddition, palette: DominantColors) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            art = addition.songs.firstOrNull()?.toSongArt(),
            palette = palette,
            initial = addition.subject.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier.size(SUBJECT_SIZE).clip(RoundedCornerShape(10.dp)),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                // The count and the name both come out of PlaylistAdditions, so what the sheet
                // claims is about to be added is what the addition actually holds.
                text = PlaylistAdditions.sheetTitle(addition),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Choose a playlist",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One choice in the picker: an emblem, a name, and what is already in it. */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    emblem: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        emblem()
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PickerLoading() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

/**
 * Holds the picker for a browse surface: nothing while [addition] is null, the sheet while it is
 * not.
 *
 * Three screens need exactly this, and three copies of "remember a nullable addition, show a sheet
 * for it" is three chances for one of them to forget to clear it on dismiss — which leaves a sheet
 * that reopens itself on the next recomposition.
 */
@Composable
fun AddToPlaylistHost(
    vm: PlaylistViewModel,
    addition: PlaylistAddition?,
    onDismiss: () -> Unit,
) {
    if (addition == null) return
    // An empty selection cannot be filed anywhere, and a sheet headed "Nothing to add" over a list
    // of playlists is a dead end the user has to back out of. It is reachable: an album page can
    // be composed for a record whose rows have just left the library. Cleared rather than merely
    // not drawn — returning without dismissing would leave the caller holding a request that never
    // resolves, and the affordance would look broken until the screen was left.
    if (addition.isEmpty) {
        LaunchedEffect(addition) { onDismiss() }
        return
    }
    AddToPlaylistSheet(vm = vm, addition = addition, onDismiss = onDismiss)
}
