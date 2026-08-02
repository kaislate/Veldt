// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.paletteWash
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The stack's front tile — the size the mosaic (Task 7) gets on this surface. */
private val COVER_SIZE = 60.dp

/** Total width of the stacked emblem: the cover plus the two records showing behind it. */
private val STACK_WIDTH = 76.dp

private val COVER_SHAPE = RoundedCornerShape(12.dp)
private val ROW_SHAPE = RoundedCornerShape(18.dp)

/** How far each receding card in the stack sits behind the one in front of it. */
private val STACK_STEP = 12.dp

/**
 * The playlists tab.
 *
 * The idiom is deliberately NOT the bare list every other player ships. A playlist here is a
 * **stack**: its cover sits in front of two receding cards, so a row reads as a collection of
 * records rather than as a line of text with a thumbnail. That emblem is also the Task 7 seam —
 * the front tile is the only thing the mosaic replaces, and the stack around it is untouched.
 *
 * The import affordance is the first row rather than a floating button. A FAB would cover the last
 * playlist in the list, and on a tab whose empty state is "you have no playlists", the thing that
 * creates one belongs IN the list, where it is also the first thing the eye lands on.
 */
@Composable
fun PlaylistsScreen(
    vm: PlaylistViewModel,
    onOpenPlaylist: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val outcome by vm.importOutcome.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    // The neutral palette, as on every other browse surface: a list themed by twenty different
    // covers at once is noise. Per-artwork colour is a detail-screen and now-playing concern.
    val palette = ColorExtractor.extract(null)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri.toString())
    }
    val openPicker: () -> Unit = {
        // `*/*`, not the m3u mime types. Providers routinely report a playlist as
        // `application/octet-stream` or nothing at all, and a filtered picker greys out the very
        // file the user came to choose. PlaylistImporter's size cap and parser are what handle a
        // wrong pick, and they report it as a message.
        //
        // No persistable grant is taken: the import happens now and never reads the document
        // again. Persisting a permission the app has no further use for is a slot spent for
        // nothing — and the case it would supposedly protect, a grant that lapses before the
        // read, is handled where it actually has to be, in PlaylistViewModel.import.
        val launched = runCatching { picker.launch(arrayOf("*/*")) }
        if (launched.isFailure) vm.reportPickerUnavailable()
    }

    // Which playlist a dialog is currently about. Held here rather than per-row so the dialog
    // survives the row scrolling out from under it.
    var renaming by remember { mutableStateOf<PlaylistCard?>(null) }
    var deleting by remember { mutableStateOf<PlaylistCard?>(null) }

    when (val current = state) {
        PlaylistsUiState.Loading -> PlaylistsLoading(palette, contentPadding, modifier)

        PlaylistsUiState.Empty -> EmptyState(
            palette = palette,
            title = "No playlists yet",
            body = "Import an .m3u or .m3u8 file and Veldt will keep every track it names — " +
                "including the ones that aren't on this device yet.",
            actionLabel = if (importing) "Importing…" else "Import a playlist",
            onAction = openPicker,
            contentPadding = contentPadding,
            modifier = modifier,
        )

        is PlaylistsUiState.Ready -> {
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
                item(key = "import") {
                    ImportInvitation(
                        palette = palette,
                        importing = importing,
                        onClick = openPicker,
                    )
                }
                itemsIndexed(current.cards, key = { _, card -> card.id }) { index, card ->
                    PlaylistStackRow(
                        card = card,
                        palette = palette,
                        onClick = { onOpenPlaylist(card.id) },
                        onRename = { renaming = card },
                        onDelete = { deleting = card },
                        modifier = Modifier.staggeredEntrance(index, reduced),
                    )
                }
            }
        }
    }

    renaming?.let { card ->
        RenameDialog(
            card = card,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                vm.rename(card.id, name)
                renaming = null
            },
        )
    }

    deleting?.let { card ->
        DeleteDialog(
            card = card,
            onDismiss = { deleting = null },
            onConfirm = {
                vm.delete(card.id)
                deleting = null
            },
        )
    }

    outcome?.let { report ->
        ImportReportDialog(
            outcome = report,
            onDismiss = vm::dismissImport,
            onOpen = { playlistId ->
                vm.dismissImport()
                onOpenPlaylist(playlistId)
            },
            onRetry = {
                vm.dismissImport()
                openPicker()
            },
        )
    }
}

/**
 * Held while Room is answering AND while the first resolve runs.
 *
 * A real state rather than a blank frame: resolving twenty playlists against a large library is
 * not instant, and rendering "No playlists yet" over it would be the same lie P1.3 told about a
 * library mid-scan.
 */
@Composable
internal fun PlaylistsLoading(
    palette: DominantColors,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BrowseMessage(
        palette = palette,
        title = "Gathering your playlists…",
        body = "Veldt is matching each playlist against the music on this device.",
        contentPadding = contentPadding,
        modifier = modifier,
        emblem = {
            Box(
                modifier = Modifier.fillMaxSize().background(paletteWash(palette)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = palette.onBg.copy(alpha = 0.75f)) }
        },
    )
}

/**
 * The import row: a dashed outline where a cover would be.
 *
 * Dashed rather than filled on purpose — it reads as a slot waiting for something instead of as a
 * playlist that happens to have no artwork, which is what a solid tile with a plus in it looks
 * like next to three real ones.
 */
@Composable
private fun ImportInvitation(
    palette: DominantColors,
    importing: Boolean,
    onClick: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = 6.dp)
            .clip(ROW_SHAPE)
            .clickable(enabled = !importing, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(COVER_SIZE)
                .drawBehind {
                    drawRoundRect(
                        color = outline,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(
                                floatArrayOf(10.dp.toPx(), 8.dp.toPx()),
                            ),
                        ),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (importing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
            } else {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                // Lined up with a playlist row's text, which starts past the whole stack rather
                // than past this single tile.
                .padding(start = STACK_WIDTH - COVER_SIZE + 12.dp),
        ) {
            Text(
                text = if (importing) "Importing…" else "Import a playlist",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Choose an .m3u or .m3u8 file",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One playlist: the stacked emblem, the name, the count, and an overflow. */
@Composable
private fun PlaylistStackRow(
    card: PlaylistCard,
    palette: DominantColors,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = 6.dp)
            .clip(ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistStack(card = card, palette = palette)
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = PlaylistPresentation.caption(card.trackCount, card.missingCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "More options for ${card.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Rename") },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * A playlist's cover with two records showing behind it.
 *
 * The receding cards are drawn FIRST and offset to the trailing side, so they peek out past the
 * cover rather than around it. They are palette-tinted rather than a second and third piece of
 * artwork: three covers fanned out at this size is mud, and the mosaic (Task 7) is where several
 * covers get to be legible.
 */
@Composable
private fun PlaylistStack(
    card: PlaylistCard,
    palette: DominantColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.size(width = STACK_WIDTH, height = COVER_SIZE),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Farthest back, faintest, smallest. Only its trailing edge is ever visible.
        RecedingCard(palette, offsetX = STACK_STEP * 2, inset = 8.dp, alpha = 0.20f)
        RecedingCard(palette, offsetX = STACK_STEP, inset = 4.dp, alpha = 0.38f)
        PlaylistCoverArt(
            covers = card.covers,
            palette = palette,
            initial = card.name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier.size(COVER_SIZE).clip(COVER_SHAPE),
        )
    }
}

@Composable
private fun RecedingCard(
    palette: DominantColors,
    offsetX: Dp,
    inset: Dp,
    alpha: Float,
) {
    Box(
        Modifier
            .offset(x = offsetX)
            .padding(vertical = inset)
            .size(width = COVER_SIZE, height = COVER_SIZE - inset * 2)
            .clip(COVER_SHAPE)
            .background(palette.accent.copy(alpha = alpha)),
    )
}

/**
 * **Task 7 seam.** The artwork that stands for a whole playlist.
 *
 * Today it draws the first cover, which is honest but plain. Task 7 replaces the BODY of this one
 * function with `PlaylistMosaic(covers, modifier)` — the callers already hand it the full,
 * album-distinct, capped list from [PlaylistPresentation.coversOf], and both the stack on this
 * screen and the full-bleed header on the detail screen pick the change up at once. Nothing else
 * has to move.
 */
@Composable
internal fun PlaylistCoverArt(
    covers: List<Song>,
    palette: DominantColors,
    initial: Char,
    modifier: Modifier = Modifier,
) {
    ArtImage(
        art = covers.firstOrNull()?.toSongArt(),
        palette = palette,
        initial = initial,
        modifier = modifier,
    )
}

// ------------------------------------------------------------------------------------ dialogs

@Composable
private fun RenameDialog(
    card: PlaylistCard,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(card.id) { mutableStateOf(card.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename playlist") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            // Disabled rather than silently ignored: a Save button that does nothing is worse
            // than one that shows it cannot. PlaylistNaming.sanitize decides, so the button and
            // the view model agree by construction.
            TextButton(
                onClick = { onConfirm(text) },
                enabled = PlaylistNaming.sanitize(text) != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(
    card: PlaylistCard,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${card.name}\"?") },
        text = {
            Text(
                "The playlist and its order are gone for good. None of the music files are " +
                    "touched.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The import report: "Imported 43 of 47 tracks", **with the four named**.
 *
 * A dialog, not a snackbar. The count is the only signal the user gets that a playlist arrived
 * incomplete, and the four names are the only way to find out WHICH tracks — a message that
 * vanishes after four seconds throws both away. It is dismissed by a deliberate tap.
 */
@Composable
private fun ImportReportDialog(
    outcome: ImportOutcome,
    onDismiss: () -> Unit,
    onOpen: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    when (outcome) {
        is ImportOutcome.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(PlaylistImportReport.headline(outcome.result)) },
            text = {
                Column(
                    // Bounded and scrollable: a playlist can name forty tracks that are not on
                    // this device, and a dialog that grows until its buttons leave the screen is
                    // a dialog that cannot be dismissed.
                    Modifier
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        PlaylistImportReport.detail(outcome.result),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    outcome.result.unresolved.forEach { entry ->
                        Column {
                            Text(
                                PlaylistImportReport.unresolvedLabel(entry),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                PlaylistImportReport.unresolvedDetail(entry),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onOpen(outcome.result.playlistId) }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        )

        is ImportOutcome.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(PlaylistImportReport.failureHeadline(outcome.failure)) },
            text = { Text(PlaylistImportReport.failureDetail(outcome.failure)) },
            // The remedy for the commonest cause — a lapsed grant — is to pick the file again,
            // so the affirmative button IS that, not an "OK" that leaves the user where they
            // started.
            confirmButton = { TextButton(onClick = onRetry) { Text("Pick a file") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
        )
    }
}
