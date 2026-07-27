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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.playback.NowPlayingState
import com.kaislate.veldtplayer.ui.motion.ArtMorph
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.motion.sharedSongArt
import com.kaislate.veldtplayer.ui.theme.CHROME_ALPHA
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.SUBTITLE_ALPHA
import com.kaislate.veldtplayer.ui.theme.onBgFor

/** Thickness of the progress hairline that doubles as the chrome's top edge. */
private val HAIRLINE = 2.dp

private const val TRACK_ALPHA = 0.15f

private val THUMB_SIZE = 48.dp
private val THUMB_CORNER = 8.dp

/**
 * Persistent chrome above the bottom bar, and the second end of the track-art morph: the
 * thumbnail here is the SAME shared element as the now-playing screen's full-bleed cover, so
 * tapping the row flies one image up the screen instead of cross-fading two.
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
 * [visible] is a parameter rather than the caller simply not composing this, because an end
 * that only exists while it is on screen cannot be an end of the transition that puts it back
 * on screen. So this stays composed for the length of the hand-over and hides ITSELF — fading
 * out, dropping its click targets, and leaving the accessibility tree, so an invisible row
 * cannot swallow taps aimed at the screen behind it. The caller drops it entirely once the
 * transition settles; `visible == false` here is a state to pass THROUGH, never to rest in.
 * See `rememberMorphLinger`.
 *
 * [artMorph] is HOISTED for the same reason [visible] is a parameter: the caller decides how
 * long this end lives, and it cannot make that decision without asking the very state this
 * row's modifier attaches. Minting one here would leave the caller holding a state no modifier
 * ever wrote, which reports "no match" forever. See `ArtMorph`.
 */
@Composable
fun MiniPlayer(
    state: NowPlayingState,
    palette: DominantColors,
    progress: () -> Float,
    visible: Boolean,
    artMorph: ArtMorph?,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // BELT AND BRACES, and currently the braces: VeldtNavHost already gates the whole call on
    // `npState.isActive` so it can skip collecting the position ticker for an empty queue.
    // Neither guard is the single source of truth — this one keeps the component honest for a
    // caller that does not gate, and dropping it would make that caller render a row with no
    // song in it. Do not delete either half on the strength of the other.
    if (!state.isActive) return

    // Read in the LAYER phase, never unwrapped here: unwrapping would recompose the whole
    // row on every frame of the fade.
    val alpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = Motion.gentle,
        label = "miniPlayerAlpha",
    )

    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha.value }
            // Hidden means gone, for a screen reader too. The alpha above is a draw effect
            // and semantics do not care about it, so without this TalkBack would still find
            // a whole mini-player sitting on top of the now-playing screen.
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { })
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
                // Not attached at all when hidden, rather than disabled: the Scaffold draws
                // its bottom bar OVER the content, so an invisible-but-clickable row would
                // pause playback for anyone tapping the lower part of the now-playing screen.
                .then(
                    if (!visible) Modifier else Modifier.clickable(
                        onClickLabel = "Open now playing",
                        role = Role.Button,
                        onClick = onOpen,
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtImage(
                art = state.art,
                palette = palette,
                initial = state.initial,
                // A fixed box, never an unbounded one — ArtImage's loading state fills its
                // parent. sharedSongArt sits BEFORE clip so the rounding travels with the
                // shared node, matching AlbumCard.
                modifier = Modifier
                    .size(THUMB_SIZE)
                    .sharedSongArt(artMorph, visible = visible)
                    .clip(RoundedCornerShape(THUMB_CORNER)),
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
                    color = palette.onBg.copy(alpha = SUBTITLE_ALPHA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Disabled while stalled, like the full transport: after the error bound engages
            // the player is IDLE and neither of these would do anything. See isStalled.
            //
            // The tint is dimmed EXPLICITLY, via onBgFor: IconButton signals "disabled" by
            // lowering LocalContentColor, which the explicit palette `tint` overrides.
            val canToggle = visible && !state.isStalled
            IconButton(onClick = onToggle, enabled = canToggle) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = palette.onBgFor(canToggle),
                )
            }
            val canSkip = visible && state.hasNext && !state.isStalled
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
