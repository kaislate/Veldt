package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.playback.NowPlayingState
import com.kaislate.veldtplayer.playback.RepeatMode
import com.kaislate.veldtplayer.ui.components.ArtBackdrop
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.sharedSongArt
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.onBgFor
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette

/** How far down the surface must be dragged, in dp, before releasing dismisses it. */
private const val DISMISS_DRAG_DP = 96f

/** Fraction of the width the cover occupies — air on both sides, not a bleed. */
private const val ART_WIDTH = 0.82f

private val ART_CORNER = 20.dp
private val SCREEN_INSET = 24.dp
private val STACK_GAP = 20.dp
private val TRANSPORT_GAP = 8.dp
private val PLAY_BUTTON = 72.dp
private val PLAY_GLYPH = 44.dp

private const val SUBTITLE_ALPHA = 0.72f
private const val INACTIVE_ALPHA = 0.5f

/**
 * The now-playing surface — the screen the whole slice converges on.
 *
 * Everything visible here is themed by ONE animated palette derived from the current cover:
 * the drifting blurred backdrop, the art placeholder, the wave, the accent on the shuffle and
 * repeat toggles. Because the palette animates rather than steps, a track change makes the
 * entire screen DRIFT to the new record's colours over ~600ms instead of hard-cutting the way
 * every other player does (spec §6). That behaviour is the point of the screen, not
 * decoration on it.
 *
 * The cover is one end of the track-art morph; the other is the mini-player thumbnail this
 * screen replaced on the way in. Which of the pair is the LIVE end is declared explicitly
 * rather than inferred by a `sharedElement` from an `AnimatedVisibilityScope`, because the
 * other end is chrome with no scope of its own worth borrowing — see `Modifier.sharedSongArt`.
 * [artVisible] is that declaration, and is true exactly while this is the current route.
 *
 * **A LAMBDA, not a `Boolean`, and the return morph does not run without that.** On a pop,
 * navigation keeps this destination composed for the length of the exit and RE-INVOKES it
 * whenever its own composition invalidates — what never happens is the PARENT re-invoking it
 * with fresh arguments, because the parent's `composable { }` lambda is not re-run for an
 * entry that is leaving. A `Boolean` therefore stays frozen at whatever it was handed on the
 * way in, while the mini-player — chrome in the scaffold's `bottomBar`, which does recompose —
 * has already reclaimed the element. Both ends then claim `visible == true` at once, the match
 * has two live claimants instead of a hand-over, and the cover contends rather than travelling
 * (measured: 807 → 96 → 798 → 807 → 96 in 294ms). Read HERE in composition, the lambda's own
 * snapshot read is what invalidates this screen while it is leaving, so the departing end goes
 * false on the same frame the mini-player's end comes back — the outbound leg exactly mirrored.
 *
 * **The documented-looking alternative was tried and is measurably wrong.** Deriving this from
 * this destination's own `AnimatedVisibilityScope.transition.targetState` is snapshot-backed
 * public API and needs no parameter at all — but it does not flip when the pop STARTS, it flips
 * when the exit animation is dispatched, ~184ms later on the reference device. The mini-player
 * re-attaches at frame 0 regardless (it reads the back stack), so the two ends stop being
 * handed over on the same frame and the return leg SNAPS: measured 807 at t+0, 96 at t+161, no
 * frame between. What this pair actually requires is not "a documented signal" but ONE signal
 * read by both ends, and the back stack is the only one that flips at frame 0 for both.
 */
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    artVisible: () -> Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, never bare collectAsState: positionMs is a WhileSubscribed
    // 250ms poll and only stops when its collectors detach, so a backgrounded screen holding
    // a plain collector would keep the ticker running behind the user's back.
    val state by vm.nowPlaying.collectAsStateWithLifecycle()
    val position by vm.positionMs.collectAsStateWithLifecycle()
    val targetPalette by vm.palette.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()

    // Colours DRIFT to the new track instead of cutting (spec §6).
    val palette = rememberAnimatedPalette(targetPalette)

    // Accumulated, and acted on at RELEASE. Reacting to a single drag delta would fire
    // onCollapse once per pointer event past the threshold, popping several entries off the
    // back stack for one gesture.
    var draggedY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val threshold = DISMISS_DRAG_DP.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { draggedY = 0f },
                    onDragEnd = {
                        if (draggedY > threshold) onCollapse()
                        draggedY = 0f
                    },
                    onDragCancel = { draggedY = 0f },
                ) { _, dragAmount -> draggedY += dragAmount }
            }
    ) {
        // fillMaxSize under a fillMaxSize Box, i.e. BOUNDED constraints. It has to be: the
        // backdrop's ArtImage draws its loading state with fillMaxSize, which collapses to
        // the minimum constraint under an unbounded parent — the backdrop would measure ~0
        // and then pop to full screen when the bitmap arrived.
        ArtBackdrop(
            art = state.art,
            palette = palette,
            reducedMotion = reduced,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            Modifier
                .fillMaxSize()
                // The window insets are read HERE rather than taken from the Scaffold's
                // PaddingValues. This screen hides the bottom chrome, so those PaddingValues
                // describe a bar on its way out and would shift the transport at the end of
                // the fade. The backdrop above is deliberately left to bleed behind both
                // system bars, which is why the inset lands on the content and not the Box.
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = SCREEN_INSET),
            verticalArrangement = Arrangement.spacedBy(STACK_GAP, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!state.isActive) {
                // Reachable: a restored back stack can land on this route with the queue
                // empty. Saying so beats rendering a blank screen with dead controls.
                Text(
                    text = "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onBg,
                    textAlign = TextAlign.Center,
                )
            } else {
                // Read in COMPOSITION, deliberately: the snapshot read is what invalidates
                // this screen while it is exiting, so the departing end of the morph can
                // stop being the live one. See the KDoc.
                val artIsLiveEnd = artVisible()
                ArtImage(
                    art = state.art,
                    palette = palette,
                    initial = state.initial,
                    // sharedSongArt before clip, so the rounding travels with the shared
                    // node rather than being re-applied at the destination.
                    modifier = Modifier
                        .fillMaxWidth(ART_WIDTH)
                        .aspectRatio(1f)
                        .sharedSongArt(state.songId, visible = artIsLiveEnd)
                        .clip(RoundedCornerShape(ART_CORNER)),
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = palette.onBg,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Both already routed through DisplayNames by NowPlayingState —
                        // there is no second blank-tag rule anywhere in the UI.
                        text = "${state.artist} · ${state.album}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.onBg.copy(alpha = SUBTITLE_ALPHA),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                WaveScrubBar(
                    positionMs = position,
                    durationMs = state.durationMs,
                    palette = palette,
                    reducedMotion = reduced,
                    onSeek = vm::seekTo,
                    modifier = Modifier.fillMaxWidth(),
                )

                Transport(
                    state = state,
                    palette = palette,
                    onShuffle = { vm.setShuffle(!state.shuffle) },
                    onPrevious = vm::previous,
                    onToggle = vm::toggle,
                    onNext = vm::next,
                    onRepeat = vm::cycleRepeat,
                )
            }
        }

        // Docked to the corner rather than placed in the centred stack above: a way back
        // belongs at the edge of the surface, not in the middle of the artwork.
        //
        // A VISIBLE affordance, not only the drag. A downward swipe is undiscoverable, and
        // — the reason this is not a preference — it is unreachable with TalkBack on, which
        // would leave the screen a one-way trip for a screen-reader user.
        IconButton(
            onClick = onCollapse,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 8.dp),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Collapse",
                tint = palette.onBg,
            )
        }
    }
}

/**
 * The five transport controls.
 *
 * Play/pause and the two skips go DEAD when the player is stalled, because they genuinely
 * are: once PlaybackConnection's error bound stops skipping through an undecodable queue the
 * player is left IDLE and none of those three ever calls prepare() again. Shuffle and repeat
 * stay live — they set player fields and take effect on the next real playback regardless.
 */
@Composable
private fun Transport(
    state: NowPlayingState,
    palette: DominantColors,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
) {
    val live = !state.isStalled
    val canPrevious = state.hasPrevious && live
    val canNext = state.hasNext && live

    Row(
        horizontalArrangement = Arrangement.spacedBy(TRANSPORT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShuffle) {
            Icon(
                Icons.Filled.Shuffle,
                // The toggles announce their STATE, not just their name. A tint change is
                // the only thing that distinguishes on from off, and a tint is invisible
                // to a screen reader.
                contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off",
                tint = if (state.shuffle) palette.accent
                else palette.onBg.copy(alpha = INACTIVE_ALPHA),
            )
        }
        IconButton(onClick = onPrevious, enabled = canPrevious) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = palette.onBgFor(canPrevious),
            )
        }
        IconButton(onClick = onToggle, enabled = live, modifier = Modifier.size(PLAY_BUTTON)) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = palette.onBgFor(live),
                modifier = Modifier.size(PLAY_GLYPH),
            )
        }
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = palette.onBgFor(canNext),
            )
        }
        IconButton(onClick = onRepeat) {
            Icon(
                imageVector = if (state.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne
                else Icons.Filled.Repeat,
                contentDescription = when (state.repeat) {
                    RepeatMode.OFF -> "Repeat off"
                    RepeatMode.ALL -> "Repeat all"
                    RepeatMode.ONE -> "Repeat one"
                },
                tint = if (state.repeat == RepeatMode.OFF)
                    palette.onBg.copy(alpha = INACTIVE_ALPHA) else palette.accent,
            )
        }
    }
}
