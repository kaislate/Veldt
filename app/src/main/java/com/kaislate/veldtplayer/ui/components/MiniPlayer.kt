// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.playback.NowPlayingState
import com.kaislate.veldtplayer.ui.theme.CHROME_ALPHA
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.onBgFor

/** Thickness of the progress hairline that doubles as the chrome's top edge. */
private val HAIRLINE = 2.dp

private const val TRACK_ALPHA = 0.15f

private val THUMB_SIZE = 48.dp
/**
 * The thumbnail's corner radius. Internal rather than private because the now-playing sheet's
 * cover lands on this thumbnail and has to land on this exact rounding (`sheetArtFlight`).
 */
internal val MINI_PLAYER_THUMB_CORNER = 8.dp

/**
 * Persistent chrome above the bottom bar, and where the now-playing sheet rests when it is
 * collapsed: the sheet's cover lands on this row's thumbnail, and the row fades back in over
 * the last part of the sheet's travel. Tapping the row opens the sheet; dragging it up drags the
 * sheet open with the finger.
 *
 * None of that is decided here. The caller passes the sheet's half through [modifier] (the row's
 * fade, its position, the upward drag) and [thumbModifier] (where the cover lands, and hiding the
 * thumbnail while the flying cover stands in for it) — see `miniPlayerOfSheet` and
 * `miniPlayerThumbOfSheet` in `ui/nowplaying/PlayerSheet.kt`. This row stays a plain row.
 *
 * Its ground and its progress hairline are the animated palette, so the browse screens drift
 * in colour with the current track even though their lists stay on the neutral theme — the
 * one place the whole app's colour is visible at once.
 *
 * [progress] is a LAMBDA, deliberately. It is fed by the 250ms position ticker; taken as a
 * `Float` it would recompose this row — art, both labels, both buttons — four times a second
 * for the life of the app. Read inside [drawBehind] it costs one draw invalidation of a 2dp
 * strip and no recomposition at all.
 *
 * It used to take a `visible` flag and hide itself, because it was one end of a nav-transition
 * shared-element morph and had to stay composed, invisible, while the now-playing destination
 * covered it. The sheet is drawn OVER the whole app instead — this row included — so there is no
 * longer a state in which the row is composed but must not be seen or pressed.
 */
@Composable
fun MiniPlayer(
    state: NowPlayingState,
    palette: DominantColors,
    progress: () -> Float,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    thumbModifier: Modifier = Modifier,
) {
    // BELT AND BRACES, and currently the braces: VeldtNavHost already gates the whole call on
    // `npState.isActive` so it can skip collecting the position ticker for an empty queue.
    // Neither guard is the single source of truth — this one keeps the component honest for a
    // caller that does not gate, and dropping it would make that caller render a row with no
    // song in it. Do not delete either half on the strength of the other.
    if (!state.isActive) return

    Column(
        modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(HAIRLINE)
                .drawBehind {
                    drawRect(palette.onBg.copy(alpha = TRACK_ALPHA))
                    drawRect(
                        color = palette.accent,
                        size = Size(size.width * progress().coerceIn(0f, 1f), size.height),
                    )
                }
        )
        Row(
            Modifier
                .fillMaxWidth()
                // Same translucency as the navigation bar below it, so the two read as one
                // pane of chrome with the library passing behind rather than as two slabs.
                .background(palette.bg.copy(alpha = CHROME_ALPHA))
                .clickable(
                    onClickLabel = "Open now playing",
                    role = Role.Button,
                    onClick = onOpen,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtImage(
                art = state.art,
                palette = palette,
                initial = state.initial,
                // A fixed box, never an unbounded one — ArtImage's loading state fills its
                // parent. The caller's thumbModifier goes between the size and the clip, so
                // what it measures is exactly the 48dp square the sheet's cover lands on.
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .then(thumbModifier)
                    .clip(RoundedCornerShape(MINI_PLAYER_THUMB_CORNER)),
            )
            // weight(1f) so both labels ellipsize against the row rather than against
            // whatever width the longer of them happens to want.
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onBg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBgSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Disabled while stalled, like the full transport: after the error bound engages
            // the player is IDLE and neither of these would do anything. See isStalled.
            //
            // The tint is dimmed EXPLICITLY, via onBgFor: IconButton signals "disabled" by
            // lowering LocalContentColor, which the explicit palette `tint` overrides.
            val canToggle = !state.isStalled
            IconButton(onClick = onToggle, enabled = canToggle) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = palette.onBgFor(canToggle),
                )
            }
            val canSkip = state.hasNext && !state.isStalled
            IconButton(onClick = onNext, enabled = canSkip) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next",
                    tint = palette.onBgFor(canSkip),
                )
            }
        }
    }
}
