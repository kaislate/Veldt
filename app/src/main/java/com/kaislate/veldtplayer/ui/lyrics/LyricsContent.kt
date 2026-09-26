// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.kaislate.veldtplayer.data.lyrics.LyricLine
import com.kaislate.veldtplayer.data.lyrics.Lyrics
import com.kaislate.veldtplayer.data.lyrics.activeLineIndex
import com.kaislate.veldtplayer.ui.nowplaying.LyricsUi
import com.kaislate.veldtplayer.ui.nowplaying.NowPlayingViewModel
import com.kaislate.veldtplayer.ui.theme.BackdropText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * The one lyrics renderer behind both the in-place pane on now-playing and the full-screen
 * `lyrics` route (spec §7).
 *
 * **Colour is exactly two tones and never an alpha.** Every glyph here is drawn in
 * [BackdropText.primary] (the active synced line, plain lyrics, the "no lyrics" message) or
 * [BackdropText.secondary] (every other synced line, the attribution, the loading indicator) —
 * the pair `ArtSeed.backdropText` SOLVED against the composited backdrop this sits on. The
 * contrast guarantee is a property of those exact colours on that exact ground, so "dimming"
 * the inactive lines with alpha, or putting a surface behind them, would each silently void it
 * (findings 14/16). Emphasis on the active line is weight, not transparency.
 *
 * [footerAction] is the trailing slot of the attribution row — the pane's expand button — so
 * the pane's one extra control does not have to float over the lines it would then cover.
 */
@Composable
fun LyricsContent(
    state: LyricsUi,
    positionMs: Long,
    text: BackdropText,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    lineStyle: TextStyle = MaterialTheme.typography.titleMedium,
    footerAction: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                // Hidden is only ever seen here for the frame between a surface composing and
                // its visibility effect reaching the view model; it is about to become Loading,
                // so it is drawn as Loading rather than as a one-frame blank.
                LyricsUi.Hidden, LyricsUi.Loading -> CircularProgressIndicator(
                    color = text.secondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
                is LyricsUi.None -> NoLyrics(state.onlineEnabled, text, onOpenSettings)
                is LyricsUi.Shown -> when (val lyrics = state.resolved.lyrics) {
                    is Lyrics.Synced -> SyncedLyrics(
                        lines = lyrics.lines,
                        positionMs = positionMs,
                        text = text,
                        lineStyle = lineStyle,
                        onSeek = onSeek,
                    )
                    is Lyrics.Plain -> PlainLyrics(lyrics.text, text, lineStyle)
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val source = (state as? LyricsUi.Shown)?.resolved?.source
            Text(
                text = source?.let(::attribution).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = text.secondary,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            footerAction?.invoke()
        }
    }
}

@Composable
private fun NoLyrics(onlineEnabled: Boolean, text: BackdropText, onOpenSettings: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "No lyrics for this track",
            style = MaterialTheme.typography.bodyLarge,
            color = text.primary,
            textAlign = TextAlign.Center,
        )
        // Only while LRCLIB is actually off — `onlineEnabled` is read at resolution time, see
        // LyricsUi.None. Offering to turn on something already on would be a dead end.
        if (!onlineEnabled) {
            TextButton(
                onClick = onOpenSettings,
                // The theme's primary is solved against the theme surface, not this backdrop;
                // the link takes the backdrop's own solved tone like every other glyph here.
                colors = ButtonDefaults.textButtonColors(contentColor = text.primary),
            ) {
                Text("Turn on online lyrics in Settings", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PlainLyrics(body: String, text: BackdropText, lineStyle: TextStyle) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            body,
            style = lineStyle,
            color = text.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        )
    }
}

/**
 * The synced list. The active line is centred by an animated scroll whenever it changes, unless
 * the user has recently dragged the list — see [shouldAutoFollow] and [FOLLOW_SUSPEND_MS].
 *
 * The suspension deadline is also a KEY of the follow effect, so a drag that ends restarts the
 * wait, and when the deadline passes the effect re-centres on the current line by itself —
 * without it the list would stay wherever the user left it until the next line change, which on
 * a long held note can be many seconds after the 4 s the spec promises.
 */
@Composable
private fun SyncedLyrics(
    lines: List<LyricLine>,
    positionMs: Long,
    text: BackdropText,
    lineStyle: TextStyle,
    onSeek: (Long) -> Unit,
) {
    // Keyed on the lines: a new track's lyrics start from the top, not from wherever the last
    // track's list happened to be scrolled.
    val listState = remember(lines) { LazyListState() }
    val active = activeLineIndex(lines, positionMs)
    var suspendedUntil by remember(lines) { mutableLongStateOf(0L) }
    // First placement snaps; only line CHANGES animate. Opening lyrics mid-song should land on
    // the current line, not scroll the whole song past the user.
    val placed = remember(lines) { booleanArrayOf(false) }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> suspendedUntil = FOLLOW_SUSPENDED_WHILE_DRAGGING
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    suspendedUntil = suspendFollowAfterDrag(SystemClock.uptimeMillis())
            }
        }
    }

    LaunchedEffect(listState, active, suspendedUntil) {
        if (active < 0) return@LaunchedEffect
        val now = SystemClock.uptimeMillis()
        if (!shouldAutoFollow(now, suspendedUntil)) {
            if (suspendedUntil == FOLLOW_SUSPENDED_WHILE_DRAGGING) return@LaunchedEffect
            delay(suspendedUntil - now)
        }
        // Nothing to centre against until the list has a viewport.
        snapshotFlow { listState.layoutInfo.viewportSize.height }.first { it > 0 }
        listState.centreOn(active, animate = placed[0])
        placed[0] = true
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lines) { index, line ->
            val isActive = index == active
            Text(
                text = lineLabel(line.text),
                style = lineStyle,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) text.primary else text.secondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (isActive) stateDescription = "Current line" }
                    .clickable(onClickLabel = "Play from this line") { onSeek(line.timeMs) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Scrolls so [index] sits in the middle of the viewport: `animateScrollToItem` with an offset
 * of half the viewport minus half the item (negative, i.e. the item lands BELOW the top edge by
 * that much). The item's own height is used when it is on screen; otherwise the mean of what is
 * on screen, which is exact enough for lines of one style and is corrected by the next change.
 * Near either end the list's own bounds clamp this, which is the right behaviour — the first
 * lines sit at the top rather than below a blank half-screen.
 */
private suspend fun LazyListState.centreOn(index: Int, animate: Boolean) {
    val info = layoutInfo
    val viewport = info.viewportEndOffset - info.viewportStartOffset
    val visible = info.visibleItemsInfo
    val itemSize = visible.firstOrNull { it.index == index }?.size
        ?: if (visible.isEmpty()) 0 else visible.sumOf { it.size } / visible.size
    val offset = -(viewport / 2 - itemSize / 2)
    if (animate) animateScrollToItem(index, offset) else scrollToItem(index, offset)
}

/**
 * Makes every vertical drag that STARTS inside this element end inside it — the in-place pane's
 * half of Review Focus 1, so scrolling lyrics never collapses now-playing.
 *
 * Why this works against now-playing's root `detectVerticalDragGestures`: on the Main pass,
 * pointer events travel from the innermost node outwards, so this detector (an ancestor of the
 * lyrics list, a descendant of the root) sees each change after the list and before the root.
 * Both detectors measure the same pointer against the same touch slop, so they cross it on the
 * same event; this one consumes that change (`awaitVerticalTouchSlopOrCancellation`'s slop
 * callback consumes, and every later move is consumed below), and the root's slop wait returns
 * null the moment it sees a consumed change — its drag never starts, `onDragEnd` never runs,
 * `onCollapse` is never called. When the list itself is the one that crosses slop (it is
 * scrollable, and `Modifier.scrollable` consumes once it is dragging), it has consumed first and
 * this detector simply cancels too — either way the root sees consumed changes only.
 *
 * Below touch slop nothing is consumed, so a tap on a line is still a tap: consuming small
 * movements would trip the clickable's own "a consumed change cancels the press" check.
 */
fun Modifier.consumeVerticalDrags(): Modifier = pointerInput(Unit) {
    detectVerticalDragGestures { change, _ -> change.consume() }
}

/**
 * Registers the calling surface as a lyrics viewer while [visible] AND its lifecycle is at least
 * STARTED — see [NowPlayingViewModel.setLyricsVisible] and [lyricsClaimActive]. A
 * [DisposableEffect] keyed on that combined answer, so backgrounding the app (the entry drops to
 * CREATED while now-playing stays composed) releases the claim and returning re-takes it, and
 * leaving composition (a pop, a navigation away, the pane swapped back to the artwork) always
 * releases it.
 */
@Composable
fun LyricsVisibleWhile(vm: NowPlayingViewModel, visible: Boolean) {
    val viewer = remember { Any() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = lyricsClaimActive(visible, lifecycle)
    DisposableEffect(vm, active) {
        if (active) vm.setLyricsVisible(viewer, true)
        onDispose { vm.setLyricsVisible(viewer, false) }
    }
}

/**
 * Where a lyrics region sits on the backdrop, so its two text tones can be solved against the
 * scrim at its TOPMOST line rather than at the title band [scrimAtText] was calibrated for.
 *
 * The backdrop's scrim is a vertical gradient that strengthens downward, so the top edge of the
 * region is the weakest scrim any lyric line sits under — lines scroll up to it and no further.
 * [topFraction] starts at `0` (the top of the backdrop, the weakest value anywhere) until both
 * ends have been laid out, so the first frame errs toward the floor, never above it.
 *
 * Mark the backdrop's box with [lyricsBackdrop] and the lyrics region with [lyricsRegion].
 * Positions are read in `onGloballyPositioned`, which dispatches parents before children, so the
 * backdrop's coordinates are always current when the region reports.
 */
@Stable
class LyricsGround {
    internal var backdrop: LayoutCoordinates? = null
    var topFraction by mutableFloatStateOf(0f)
        internal set
}

@Composable
fun rememberLyricsGround(): LyricsGround = remember { LyricsGround() }

fun Modifier.lyricsBackdrop(ground: LyricsGround): Modifier =
    onGloballyPositioned { ground.backdrop = it }

fun Modifier.lyricsRegion(ground: LyricsGround): Modifier = onGloballyPositioned { region ->
    val backdrop = ground.backdrop?.takeIf { it.isAttached } ?: return@onGloballyPositioned
    if (!region.isAttached) return@onGloballyPositioned
    ground.topFraction = regionTopFraction(
        regionTopPx = backdrop.localPositionOf(region, Offset.Zero).y,
        backdropHeightPx = backdrop.size.height.toFloat(),
    )
}
