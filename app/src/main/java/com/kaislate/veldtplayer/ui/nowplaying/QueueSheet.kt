// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.theme.DISABLED_ALPHA
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.SUBTITLE_ALPHA

private val ROW_ART = 40.dp
private val ROW_ART_CORNER = 6.dp
private val ROW_SHAPE = RoundedCornerShape(12.dp)
private val SHEET_INSET = 12.dp
private val ROW_INSET = 8.dp

private const val HANDLE_ALPHA = 0.4f

/** How much of the accent tints the currently-playing row's ground. */
private const val CURRENT_ROW_TINT = 0.14f

/**
 * The current queue with tap-to-jump. Drag-to-reorder is deliberately P1.4 — it needs a
 * reorderable-list implementation and is not on the critical path to a daily driver.
 *
 * Palette-themed rather than theme-themed, which is why it does not reuse
 * [com.kaislate.veldtplayer.ui.components.SongRow]: that row paints its labels with
 * `MaterialTheme.colorScheme.onSurface`, which is correct on the browse surface and has no
 * guaranteed contrast at all against [DominantColors.bg]. The sheet is an extension of the
 * now-playing surface and reads its colours from the same animated palette, so it drifts
 * with the artwork like everything else on that screen.
 *
 * [canJump] is not decoration. When `PlaybackConnection`'s error bound has engaged, the
 * player is left IDLE and `skipToQueueIndex` — a bare `seekTo` — silently does nothing;
 * offering a dozen rows that each do nothing is the exact failure the transport already
 * greys itself out to avoid. The queue is still worth SHOWING in that state (it is the
 * only place to see what was lined up), so it is presented, just not as actionable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    songs: List<Song>,
    currentSongId: Long?,
    palette: DominantColors,
    canJump: Boolean,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Opened ON the current track, not at the top. A queue is read forwards from where you
    // are; landing on track 1 of 400 makes the sheet useless without a scroll every time.
    val currentIndex = remember(songs, currentSongId) {
        songs.indexOfFirst { it.id == currentSongId }.coerceAtLeast(0)
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.bg,
        contentColor = palette.onBg,
        // The default handle is tinted onSurfaceVariant, which has no defined contrast
        // against a palette background — on a dark cover it simply disappears, taking the
        // sheet's only "drag me" cue with it.
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = palette.onBg.copy(alpha = HANDLE_ALPHA))
        },
    ) {
        Text(
            text = "Queue · ${songs.size} ${if (songs.size == 1) "track" else "tracks"}",
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBg,
            modifier = Modifier.padding(start = SHEET_INSET + ROW_INSET, bottom = 8.dp),
        )
        if (!canJump) {
            Text(
                text = "Playback stopped — start a track from your library to resume.",
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBg.copy(alpha = SUBTITLE_ALPHA),
                modifier = Modifier.padding(
                    start = SHEET_INSET + ROW_INSET,
                    end = SHEET_INSET + ROW_INSET,
                    bottom = 8.dp,
                ),
            )
        }
        LazyColumn(
            state = listState,
            // The sheet lays its content out edge to edge; without this the last row sits
            // under the gesture bar.
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            modifier = Modifier.alpha(if (canJump) 1f else DISABLED_ALPHA),
        ) {
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                QueueRow(
                    song = song,
                    palette = palette,
                    isCurrent = song.id == currentSongId,
                    canJump = canJump,
                    onClick = { onJump(index) },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    palette: DominantColors,
    isCurrent: Boolean,
    canJump: Boolean,
    onClick: () -> Unit,
) {
    val title = song.displayTitle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_INSET)
            // clip before background and clickable so the tint AND the ripple both stay
            // inside the pill; a ripple on a full-bleed row reads as a list glitch.
            .clip(ROW_SHAPE)
            .background(
                if (isCurrent) palette.accent.copy(alpha = CURRENT_ROW_TINT) else Color.Transparent
            )
            .clickable(enabled = canJump, onClickLabel = "Play") { onClick() }
            // `clickable` already merges the row into one focusable node; these two say
            // WHICH row is playing, because the accent tint and the bold weight that carry
            // that meaning visually are both invisible to a screen reader.
            .semantics {
                selected = isCurrent
                if (isCurrent) stateDescription = "Now playing"
            }
            .padding(horizontal = ROW_INSET, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtImage(
            art = song.toSongArt(),
            palette = palette,
            // Derived from the DISPLAY title, so an untagged track shows the same glyph
            // here as the full-screen art does rather than a letter out of "<unknown>".
            initial = title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .size(ROW_ART)
                .clip(RoundedCornerShape(ROW_ART_CORNER)),
        )
        // weight(1f) so both labels ellipsize against the row rather than against whichever
        // of the two happens to want more width.
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                // onBg for the playing row too, NOT the accent — measured on device and it
                // is the wrong way round. `ArtSeed.colors` only solves the accent to 3:1
                // (`RATIO_LARGE`) against the ground, which is a tint budget; on the neutral-fallback
                // palettes this library produces it lands as a mid-grey and the current
                // track ends up READING AS DISABLED next to its white siblings. The accent
                // still marks the row — as the pill it sits on, where a 3:1 tint is exactly
                // what is wanted — and the weight carries the rest.
                color = palette.onBg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // DisplayNames, like every other surface — MediaStore hands back the literal
                // "<unknown>" for a missing tag and no isBlank() check in the app catches it.
                text = song.displayArtist(),
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBg.copy(alpha = SUBTITLE_ALPHA),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
