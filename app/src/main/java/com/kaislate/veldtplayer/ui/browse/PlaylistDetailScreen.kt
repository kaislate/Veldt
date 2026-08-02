// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DISABLED_ALPHA
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** As tall as the album page's cover, so the two detail screens open the same way. */
private val HEADER_HEIGHT = 300.dp

/** Height of the scrim that lands the artwork into the list's own surface colour. */
private val SCRIM_HEIGHT = 140.dp

/** See `AlbumDetailScreen`: below 1 the art lags the list, which is what reads as depth. */
private const val PARALLAX = 0.5f

/** The leading slot of a track row — the same 48dp `SongRow` uses, so the lists share a rhythm. */
private val LEADING_SIZE = 48.dp

/** How close to an edge a held drag has to get before the list starts scrolling itself. */
private val AUTOSCROLL_EDGE = 96.dp

/** Top speed of that scroll, per frame. */
private val AUTOSCROLL_MAX = 14.dp

/**
 * One playlist: the album page's language, with the two things a playlist has that a record does
 * not — an order the user owns, and entries that point at nothing.
 *
 * The header, the parallax, the scrim and the back disc are `AlbumDetailScreen`'s, deliberately:
 * a playlist is a record the user made, and it should open like one. What differs is inside the
 * list. **An entry that resolves to nothing is drawn, greyed, under the title and artist the
 * import captured, with a one-line explanation, and it cannot be played.** It is never filtered
 * out — a player that quietly shows 43 of 47 tracks is indistinguishable from one that corrupted
 * the file, which is the whole reason the schema keeps those rows.
 */
@Composable
fun PlaylistDetailScreen(
    vm: PlaylistViewModel,
    playlistId: Long,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // remember(playlistId): detail() builds a NEW cold flow per call, so recomposing without this
    // would restart the collection on every frame. Same reason AlbumDetailScreen remembers its own.
    val detailFlow = remember(playlistId) { vm.detail(playlistId) }
    val state by detailFlow.collectAsStateWithLifecycle(
        // The ONLY source of Loading — see PlaylistPresentation.detailStateOf. A mapping can never
        // produce it, so "not answered yet" can never be confused with "deleted".
        initialValue = PlaylistDetailUiState.Loading,
    )
    val palette = ColorExtractor.extract(null)

    when (val current = state) {
        PlaylistDetailUiState.Loading -> PlaylistsLoading(
            palette = palette,
            contentPadding = contentPadding,
            // Singular, and about THIS playlist. The tab's plural copy read "matching each
            // playlist against the music on this device" while one was opening.
            title = "Opening this playlist…",
            body = "Veldt is matching its tracks against the music on this device.",
            modifier = modifier,
        )

        PlaylistDetailUiState.Missing -> EmptyState(
            palette = palette,
            title = "Playlist deleted",
            body = "This playlist is no longer on the device. Nothing else was removed.",
            actionLabel = "Go back",
            onAction = onBack,
            contentPadding = contentPadding,
            modifier = modifier,
        )

        is PlaylistDetailUiState.Ready -> PlaylistDetailContent(
            vm = vm,
            playlistId = playlistId,
            state = current,
            palette = palette,
            onBack = onBack,
            contentPadding = contentPadding,
            modifier = modifier,
        )
    }
}

@Composable
private fun PlaylistDetailContent(
    vm: PlaylistViewModel,
    playlistId: Long,
    state: PlaylistDetailUiState.Ready,
    palette: DominantColors,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val rows = state.rows

    // ---- drag state -------------------------------------------------------------------------
    // The entry index the drag started on, -1 when nothing is being dragged. Kept as the ENTRY
    // index, never the lazy index, so the header items can never shift it — the one place they
    // are needed is the auto-scroll lookup, which goes through PlaylistReorder.lazyIndexOf.
    var dragFrom by remember { mutableIntStateOf(-1) }
    // Finger movement PLUS any distance the list auto-scrolled under it. This is what decides
    // where the row lands, because scrolled distance genuinely carries it further through the
    // order — and because adding it here is what keeps the row pinned under the finger while the
    // list moves beneath it.
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    // Finger movement ONLY. Used to work out where on screen the row is, which is a different
    // question — see PlaylistReorder.draggedRowCenterY.
    var fingerDeltaPx by remember { mutableFloatStateOf(0f) }
    // The row's viewport offset at the instant the drag began.
    var dragAnchorTopPx by remember { mutableFloatStateOf(0f) }
    var rowHeightPx by remember { mutableFloatStateOf(0f) }

    // Where the drag currently WOULD land. A function, not a value: called from the draw phase
    // and from the gesture's own callbacks, so no composition is invalidated per dragged pixel.
    val dragTarget: () -> Int = {
        if (dragFrom < 0) -1 else {
            PlaylistReorder.targetIndex(dragFrom, dragOffsetPx, rowHeightPx, rows.size)
        }
    }

    val density = LocalDensity.current
    val autoScrollEdgePx = with(density) { AUTOSCROLL_EDGE.toPx() }
    val autoScrollMaxPx = with(density) { AUTOSCROLL_MAX.toPx() }

    // While a drag is held near an edge, scroll the list under it. The scrolled distance is added
    // to the drag offset because the FINGER has not moved — the content has, so the dragged row
    // has travelled exactly that much further through the list. Without this, a playlist longer
    // than the screen can only be reordered among the rows that happen to be visible.
    LaunchedEffect(dragFrom) {
        if (dragFrom < 0) return@LaunchedEffect
        while (true) {
            val amount = PlaylistReorder.autoScrollPx(
                pointerY = PlaylistReorder.draggedRowCenterY(
                    anchorTopPx = dragAnchorTopPx,
                    fingerDeltaPx = fingerDeltaPx,
                    rowHeightPx = rowHeightPx,
                ),
                viewportHeightPx = listState.layoutInfo.viewportSize.height.toFloat(),
                edgePx = autoScrollEdgePx,
                maxSpeedPx = autoScrollMaxPx,
            )
            if (amount != 0f) dragOffsetPx += listState.scrollBy(amount)
            withFrameNanos { }
        }
    }

    val direction = LocalLayoutDirection.current
    Box(
        modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                end = contentPadding.calculateEndPadding(direction),
            )
    ) {
        // The parallax lives on a WRAPPER, exactly as on the album page: a graphicsLayer applied
        // to the art itself would travel with it into any transition overlay.
        Box(
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .graphicsLayer {
                    val travelled = if (listState.firstVisibleItemIndex > 0) {
                        size.height
                    } else {
                        listState.firstVisibleItemScrollOffset.toFloat().coerceAtMost(size.height)
                    }
                    translationY = -travelled * PARALLAX
                    alpha = 1f - travelled / size.height.coerceAtLeast(1f)
                }
        ) {
            // Task 7 seam — see PlaylistCoverArt. The mosaic lands here and on the tab's stack at
            // the same time; this screen already hands it the full cover list.
            PlaylistCoverArt(
                covers = state.covers,
                palette = palette,
                initial = state.name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
                modifier = Modifier.fillMaxSize(),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // NO TOP contentPadding, and the drag depends on it. `dragAnchorTopPx` is taken
            // from LazyListItemInfo.offset, which is measured from the start of the CONTENT — so
            // a top content padding would shift every row's true viewport position by that much
            // and bias the auto-scroll edge test by the same amount. The header item already
            // supplies all the top air this screen wants; if that ever changes, the anchor has to
            // gain the padding back.
            contentPadding = PaddingValues(
                bottom = contentPadding.calculateBottomPadding() + LIST_AIR,
            ),
        ) {
            // HEADER ITEM 1 of PlaylistReorder.HEADER_ITEM_COUNT.
            item(key = "header") {
                Column(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SCRIM_HEIGHT)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
                }
            }
            // HEADER ITEM 2 of PlaylistReorder.HEADER_ITEM_COUNT.
            item(key = "title") {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = state.name,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // The same caption the tab shows, from the same function, so the count a
                        // user reads on the list and the greyed rows they find in here agree.
                        text = PlaylistPresentation.caption(
                            rows.size,
                            rows.count { !it.playable },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (rows.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp),
                    ) {
                        Text(
                            "Nothing in here yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "An imported playlist fills itself. This one is empty, so there is " +
                                "nothing to play.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            itemsIndexed(rows, key = { _, row -> row.entryId }) { index, row ->
                PlaylistTrackRowView(
                    row = row,
                    palette = palette,
                    trackLabel = (index + 1).toString(),
                    onPlay = { vm.play(rows, index) },
                    onRemove = { vm.removeEntry(row.entryId) },
                    onDragStart = {
                        dragFrom = index
                        dragOffsetPx = 0f
                        fingerDeltaPx = 0f
                        val info = visibleRow(listState, index)
                        rowHeightPx = info?.size?.toFloat()?.takeIf { it > 0f } ?: rowHeightPx
                        dragAnchorTopPx = info?.offset?.toFloat() ?: 0f
                    },
                    onDrag = { delta ->
                        dragOffsetPx += delta
                        fingerDeltaPx += delta
                    },
                    onDragEnd = {
                        val target = dragTarget()
                        if (dragFrom >= 0 && target >= 0) vm.move(playlistId, dragFrom, target)
                        dragFrom = -1
                        dragOffsetPx = 0f
                    },
                    modifier = Modifier
                        // The dragged row rides above the ones it is passing. Read in
                        // composition, but `dragFrom` only changes when a drag starts or ends.
                        .zIndex(if (index == dragFrom) 1f else 0f)
                        // EVERY per-pixel read lives in here, which is the DRAW phase. Computing
                        // the shift in composition instead would invalidate the whole list on
                        // every frame of every drag — the same mistake the album header's
                        // parallax was written to avoid.
                        .graphicsLayer {
                            val from = dragFrom
                            translationY = when {
                                from < 0 -> 0f
                                index == from -> dragOffsetPx
                                else -> PlaylistReorder.displacement(
                                    index, from, dragTarget(),
                                ) * rowHeightPx
                            }
                        }
                        .background(MaterialTheme.colorScheme.surface),
                )
            }
        }

        // The scrim disc is drawn ON the button, as on the album page — nesting IconButton inside
        // a smaller box squeezes its 48dp touch target, and this is the screen's only way back.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding())
                .padding(8.dp)
                .align(Alignment.TopStart)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}

/**
 * One track row — playable or not.
 *
 * ONE composable for both states rather than two side by side. They have to be the same height to
 * the pixel (the reorder maths measures one row and applies it to every other), and they have to
 * look like the same list; two implementations kept in step by hand is how that stops being true
 * three commits later.
 *
 * **The greyed state is not `alpha` over the whole row.** Fading a row wholesale takes the text
 * below the contrast floor along with everything else. What is dimmed is chosen: the leading tile
 * becomes an outlined "missing" mark, the title drops to the secondary colour, and the row loses
 * its click entirely — a dead row that still ripples is a row the user will keep tapping.
 *
 * Removal is on a long press rather than a second trailing button. The trailing slot is the drag
 * handle, and two icons crowding every row of an art-forward list is exactly the chrome this app
 * avoids. It is on the unplayable rows too — for those it is the ONLY thing that can be done.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTrackRowView(
    row: PlaylistTrackRow,
    palette: DominantColors,
    trackLabel: String,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val song = row.song

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                // A row that cannot be played does not accept a tap — but it still accepts the
                // long press, which is the only way to be rid of it.
                onClick = { if (row.playable) onPlay() },
                onLongClick = { menuOpen = true },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(LEADING_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            if (song != null) {
                // Artwork, not the track number: a playlist's rows come from many records, so the
                // cover is what tells them apart. (The album page does the opposite, and for the
                // same reason: there, every cover would be the one already filling the header.)
                ArtImage(
                    art = song.toSongArt(),
                    palette = palette,
                    initial = row.title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                )
            } else {
                MissingMark()
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (song != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (song != null) 1f else 0.85f,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (song != null) Modifier else Modifier.semantics { disabled() },
            )
        }
        Text(
            text = trackLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.padding(end = 8.dp),
        )
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = "Reorder ${row.title}",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(24.dp)
                // On the HANDLE, not on the row. A long-press-to-drag on the row body would race
                // the row's own click and long-press, and an explicit handle is the one reorder
                // affordance a user does not have to discover by accident.
                .pointerInput(row.entryId) {
                    detectDragGestures(
                        // No position is read off the gesture. `change.position` is in the
                        // HANDLE's coordinates — a 24dp box — so testing it against the viewport
                        // would put every drag permanently inside the top auto-scroll zone. Only
                        // the DELTA is taken; where the row is on screen is reconstructed from
                        // the offset it started at. See PlaylistReorder.draggedRowCenterY.
                        onDragStart = { onDragStart() },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragEnd,
                        onDrag = { change, amount ->
                            change.consume()
                            onDrag(amount.y)
                        },
                    )
                },
        )

        Box {
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
    }
}

/**
 * What stands in for the artwork of a track that is not here.
 *
 * An outlined square with a struck-through note in it, not a blank space and not a generic
 * placeholder cover: at a glance down the list it is the shape that says "this one is different",
 * before any of the words are read.
 */
@Composable
private fun MissingMark() {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .semantics { contentDescription = PlaylistPresentation.MISSING_NOTE },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicOff,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The laid-out row for track [entryIndex], if it is on screen — which, for the row a drag just
 * started on, it is.
 *
 * Its height is MEASURED rather than assumed, because a row's height follows the type scale the
 * user chose. [PlaylistTrackRowView] is deliberately the same height whether the track resolved or
 * not, so one measurement is right for the whole list.
 *
 * The lazy index is the only place the header items enter the arithmetic, and it goes through
 * [PlaylistReorder.lazyIndexOf] rather than an inline `+ 2`.
 */
private fun visibleRow(listState: LazyListState, entryIndex: Int) =
    listState.layoutInfo.visibleItemsInfo
        .firstOrNull { it.index == PlaylistReorder.lazyIndexOf(entryIndex) }
