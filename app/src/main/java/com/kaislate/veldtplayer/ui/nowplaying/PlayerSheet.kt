// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.DragScope
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.components.MINI_PLAYER_THUMB_CORNER
import com.kaislate.veldtplayer.ui.lyrics.LyricsScreen
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The two places the now-playing sheet rests. */
internal enum class SheetValue { Expanded, Collapsed }

/**
 * How far, as a fraction of the travel, a slow drag must carry the sheet AWAY FROM THE REST IT
 * STARTED AT for the release to carry on to the other one. Far enough that a peek — a look at
 * the library behind the player, then letting go — always returns; near enough that a
 * deliberate slow pull does not have to go most of the way.
 *
 * **Measured from the starting rest, not from the top.** The first version used one absolute
 * line at 38% from the top. That is right pulling DOWN from the player and wrong pushing UP from
 * the mini-player: the same line sits 62% of the way up from the bottom, so opening the player by
 * dragging the mini-player took two-thirds of the screen (device fix round 1). Relative to where
 * the drag began, both directions ask for the same 30%.
 */
internal const val SHEET_SWITCH_DISTANCE = 0.3f

/**
 * The release speed, in dp/s, at which the DIRECTION of the flick decides and the position no
 * longer does. The spec's "~400 dp/s": a flick is quick even when it is short, and a flick
 * three centimetres down from the top is still an unambiguous "put this away".
 */
internal const val SHEET_FLING_DP_PER_S = 400f

/**
 * Where on the travel the player's own content (everything but the cover) starts and finishes
 * fading out. The top half of the travel is the PEEK — the player is still fully itself, only
 * moved — and the fade lives in the part of the travel where the sheet is becoming the
 * mini-player. Finishing at 0.85 rather than 1 means the backdrop is gone before the cover
 * reaches the thumbnail, so what lands is a cover on the mini-player, not a cover on a
 * half-transparent slab.
 */
private const val CONTENT_FADE_START = 0.5f
private const val CONTENT_FADE_END = 0.85f

/** Where the mini-player row starts fading back in. Overlaps the content fade on purpose: the
 *  two cross, so there is no stretch of the travel where neither the player nor the row reads. */
private const val MINI_FADE_START = 0.7f

/**
 * How far the Android 14+ predictive back gesture pulls the sheet down before the user commits,
 * as a fraction of the travel. The gesture's own progress runs 0..1 over a short swipe; mapped
 * 1:1 the sheet would be three-quarters gone before the user had decided anything. A small peek
 * is the platform's idiom for "this is what back will do".
 */
private const val BACK_PEEK = 0.15f

/**
 * Where a released sheet goes, from where it was let go ([offset], px down from expanded), how
 * fast ([velocity], px/s, positive = downward), how far it can travel ([height], px) and which
 * rest the drag started from ([from]).
 *
 * Velocity first: at or beyond [flingVelocity] (px/s, positive) the direction of the flick
 * decides, wherever the sheet is. Otherwise distance does: the sheet goes to the other rest once
 * it has been carried [SHEET_SWITCH_DISTANCE] of the travel away from [from], and back to [from]
 * short of that.
 *
 * Pure, so the physics that decide every release are pinned by `PlayerSheetSettleTest` rather
 * than by feel on a device.
 *
 * **Zero-height guard.** A sheet that has not been measured has no travel, and a division by it
 * would decide on `NaN`. Collapsed is the only honest answer: a sheet with nowhere to be is not
 * expanded, and collapsed is the state that shows the ordinary app with its mini-player.
 */
internal fun sheetSettleTarget(
    offset: Float,
    velocity: Float,
    height: Float,
    flingVelocity: Float,
    from: SheetValue,
): SheetValue {
    if (height <= 0f) return SheetValue.Collapsed
    if (velocity >= flingVelocity) return SheetValue.Collapsed
    if (velocity <= -flingVelocity) return SheetValue.Expanded
    val travelled = when (from) {
        SheetValue.Expanded -> offset
        SheetValue.Collapsed -> height - offset
    }
    val switch = travelled >= height * SHEET_SWITCH_DISTANCE
    return when (from) {
        SheetValue.Expanded -> if (switch) SheetValue.Collapsed else SheetValue.Expanded
        SheetValue.Collapsed -> if (switch) SheetValue.Expanded else SheetValue.Collapsed
    }
}

/** The player content's alpha at sheet [fraction] (0 = expanded, 1 = collapsed). */
internal fun sheetContentAlpha(fraction: Float): Float =
    ((CONTENT_FADE_END - fraction) / (CONTENT_FADE_END - CONTENT_FADE_START)).coerceIn(0f, 1f)

/** The mini-player row's alpha at sheet [fraction] (0 = expanded, 1 = collapsed). */
internal fun miniPlayerAlpha(fraction: Float): Float =
    ((fraction - MINI_FADE_START) / (1f - MINI_FADE_START)).coerceIn(0f, 1f)

/**
 * The now-playing sheet's position and everything that has to agree about it.
 *
 * **Why a sheet at all.** Now-playing used to be a nav destination. A destination is either on
 * top or not: dismissing it was a drag past a threshold followed by a `popBackStack()` and a
 * fixed 420 ms transition with nothing composed behind the screen, so it could not follow the
 * finger, could not be peeked behind, and could not be caught once let go. A sheet over a live
 * app can do all three, and that is the owner's whole request ("like YouTube Music").
 *
 * **One number, [fraction]: 0 is expanded, 1 is collapsed onto the mini-player.** Everything
 * reads it in the draw or layer phase — the sheet's translation, the cover's flight onto the
 * thumbnail, the content fade, the mini-player's fade — so a drag costs no recomposition. A
 * fraction rather than a pixel offset so the state survives a change of height (rotation, a
 * resize) without re-deriving anything: the pixels are always `fraction * travel`.
 *
 * **Interruptible by construction.** It implements [DraggableState], so `Modifier.draggable`
 * drives it through [drag], which takes [mutex] at [MutatePriority.UserInput]. Every settle
 * ([animateTo]) runs under the same mutex at [MutatePriority.Default], and a MutatorMutex cancels
 * a running mutation when a higher-or-equal one arrives. So grabbing the sheet mid-settle stops
 * the animation dead where it is and hands the sheet to the finger — no flag, no race.
 *
 * **Settles are springs started from the release velocity** ([Motion.sheetSettle]), bounded to
 * [0, 1] so a hard fling lands and stops rather than overshooting off the end of the travel.
 */
@Stable
class PlayerSheetState internal constructor(initiallyExpanded: Boolean) : DraggableState {

    /** 0 = expanded, 1 = collapsed. Read it in the draw / layer phase, never in composition. */
    var fraction by mutableFloatStateOf(if (initiallyExpanded) 0f else 1f)
        private set

    /**
     * Where the sheet is going, or resting: the INTENT, which leads [fraction] by the length of
     * a settle. Set at the START of [animateTo] so everything keyed on "is the player open" — the
     * back handler, the app underneath leaving the accessibility tree — follows the decision
     * immediately rather than a spring's tail later.
     */
    internal var target by mutableStateOf(
        if (initiallyExpanded) SheetValue.Expanded else SheetValue.Collapsed,
    )
        private set

    val isExpanded: Boolean get() = target == SheetValue.Expanded

    private var dragging by mutableStateOf(false)

    /**
     * The rest a drag is measured from ([sheetSettleTarget]'s `from`): [target] at the moment the
     * drag began. That is the rest the sheet was sitting at, or — for a sheet grabbed mid-settle —
     * the rest it was on its way to, which is where the user last told it to go.
     */
    private var dragOrigin = if (initiallyExpanded) SheetValue.Expanded else SheetValue.Collapsed

    /** True while a settle is animating — the draggable grabs on DOWN then, not after slop. */
    internal var isSettling by mutableStateOf(false)
        private set

    /**
     * Whether the sheet needs to exist at all. Collapsed and at rest, it is the mini-player and
     * nothing else: the full player — its drifting backdrop, its wave, its position poll — is
     * not composed. Present while anything could make it visible: a drag (including one that
     * started on the mini-player), an expanded target, or any travel left to cover.
     *
     * `target == Expanded` is in the list for a reason beyond "it is open": a drag released at
     * exactly `fraction == 1` from an expanded sheet must not drop the sheet out of composition
     * before its own `onDragStopped` has run the settle that moves [target] to collapsed.
     */
    internal val isPresent: Boolean by derivedStateOf {
        dragging || target == SheetValue.Expanded || fraction < 1f
    }

    // ---- geometry, all in the HOST's coordinates (the box both the app and the sheet fill) ----

    internal var hostCoords: LayoutCoordinates? = null
    internal var sheetCoords: LayoutCoordinates? = null
    internal var hostHeight by mutableFloatStateOf(0f)

    /** The mini-player row's top edge, or NaN before it has been laid out. */
    internal var miniTop by mutableFloatStateOf(Float.NaN)

    /** Where the cover lands: the mini-player's thumbnail. */
    internal var thumbRect by mutableStateOf(Rect.Zero)

    /**
     * Where the cover sits when the sheet is expanded, in sheet coordinates (which ARE the host's
     * at fraction 0). [Rect.Zero] while the cover is not composed — the lyrics pane is up, or the
     * sheet is absent — which is what tells the mini-player its own thumbnail must show.
     */
    internal var artRect by mutableStateOf(Rect.Zero)

    internal var reducedMotion = false
    internal var flingVelocityPx = 0f

    /**
     * How far the sheet moves between its two rests: expanded (top at 0) to collapsed (top on
     * the mini-player's top edge). Falls back to the full height before the mini-player has been
     * laid out, which only ever sends the sheet slightly further than it needed to go.
     */
    internal val travel: Float
        get() {
            val mini = miniTop
            return if (mini.isNaN() || mini <= 0f) hostHeight else mini
        }

    internal val offsetPx: Float get() = fraction * travel

    /** Whether the flying cover is currently standing in for the mini-player's thumbnail. */
    internal val coversThumb: Boolean get() = fraction < 1f && !artRect.isEmpty

    private val mutex = MutatorMutex()

    private val dragScope = object : DragScope {
        override fun dragBy(pixels: Float) = dispatchRawDelta(pixels)
    }

    /** 1:1 with the finger: a pixel of drag is a pixel of sheet. */
    override fun dispatchRawDelta(delta: Float) {
        val t = travel
        if (t > 0f) fraction = (fraction + delta / t).coerceIn(0f, 1f)
    }

    override suspend fun drag(dragPriority: MutatePriority, block: suspend DragScope.() -> Unit) {
        mutex.mutateWith(dragScope, dragPriority) {
            dragging = true
            dragOrigin = target
            try {
                block()
            } finally {
                dragging = false
            }
        }
    }

    /** A release: decide by [sheetSettleTarget], then spring there from [velocity] (px/s). */
    internal suspend fun settle(velocity: Float) {
        val t = travel
        val to = sheetSettleTarget(fraction * t, velocity, t, flingVelocityPx, from = dragOrigin)
        animateTo(to, velocity = if (t > 0f) velocity / t else 0f)
    }

    internal suspend fun expand(fast: Boolean = false) =
        animateTo(SheetValue.Expanded, spec = if (fast) Motion.sheetLaunch else Motion.sheetSettle)

    internal suspend fun collapse() = animateTo(SheetValue.Collapsed)

    /** No animation at all — for "nothing is playing any more" and the hop to Settings. */
    internal suspend fun snapTo(to: SheetValue) {
        target = to
        mutex.mutate(MutatePriority.Default) { fraction = restingFraction(to) }
    }

    /** Sets the predictive-back peek. Only meaningful inside [drag]. */
    internal fun peekForBack(progress: Float) {
        fraction = (progress * BACK_PEEK).coerceIn(0f, 1f)
    }

    /**
     * Springs to [to] from [velocity] (fractions/s). Reduced motion snaps instead; the drag
     * itself still follows the finger either way, because following a finger is not an
     * animation.
     *
     * Cancelled — by a grab, or by a newer settle — it simply stops where it is; whoever
     * cancelled it now owns the sheet.
     */
    internal suspend fun animateTo(
        to: SheetValue,
        velocity: Float = 0f,
        spec: AnimationSpec<Float> = Motion.sheetSettle,
    ) {
        target = to
        val end = restingFraction(to)
        mutex.mutate(MutatePriority.Default) {
            if (reducedMotion) {
                fraction = end
                return@mutate
            }
            isSettling = true
            try {
                val anim = Animatable(fraction).apply { updateBounds(0f, 1f) }
                val result = anim.animateTo(end, spec, velocity) { fraction = value }
                // A bound is only ever reached at `end` itself, except when a strong release
                // velocity AWAY from the target carries the sheet into the other bound first (a
                // slow-but-not-fling downward release near the threshold). Carry on from rest.
                if (result.endReason == AnimationEndReason.BoundReached && anim.value != end) {
                    anim.animateTo(end, spec) { fraction = value }
                }
            } finally {
                isSettling = false
            }
        }
    }

    internal fun rectInHost(coords: LayoutCoordinates): Rect {
        val host = hostCoords?.takeIf { it.isAttached } ?: return Rect.Zero
        return if (coords.isAttached) host.localBoundingBoxOf(coords, clipBounds = false) else Rect.Zero
    }

    internal fun rectInSheet(coords: LayoutCoordinates): Rect {
        val sheet = sheetCoords?.takeIf { it.isAttached } ?: return Rect.Zero
        return if (coords.isAttached) sheet.localBoundingBoxOf(coords, clipBounds = false) else Rect.Zero
    }

    private fun restingFraction(value: SheetValue) = if (value == SheetValue.Expanded) 0f else 1f

    companion object {
        /** Only the resting side survives rotation / process death; the geometry re-measures. */
        val Saver: Saver<PlayerSheetState, Boolean> = Saver(
            save = { it.isExpanded },
            restore = { PlayerSheetState(initiallyExpanded = it) },
        )
    }
}

/** Expanded/collapsed survives rotation (spec §10); everything else is re-measured. */
@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val sheet = rememberSaveable(saver = PlayerSheetState.Saver) {
        PlayerSheetState(initiallyExpanded = false)
    }
    // Plain fields, written every composition: neither is snapshot state, because nothing needs
    // to recompose when they change — they are read at the moment a settle starts.
    sheet.reducedMotion = rememberReducedMotion()
    sheet.flingVelocityPx = with(LocalDensity.current) { SHEET_FLING_DP_PER_S.dp.toPx() }
    return sheet
}

/** Marks the box that holds both the app and the sheet — the coordinate space of all of it. */
fun Modifier.playerSheetHost(sheet: PlayerSheetState): Modifier = onPlaced {
    sheet.hostCoords = it
    sheet.hostHeight = it.size.height.toFloat()
}

/**
 * The mini-player's half of the sheet: where it is (the collapsed rest), its fade back in over
 * the last part of the travel, and dragging it UP opening the sheet with the finger.
 *
 * The draggable is vertical-only and waits for touch slop, so a tap on the row is still a tap
 * (open), the play and next buttons still take their own taps, and a sideways movement never
 * starts it.
 */
fun Modifier.miniPlayerOfSheet(sheet: PlayerSheetState): Modifier = this
    .onGloballyPositioned {
        val r = sheet.rectInHost(it)
        sheet.miniTop = if (r.isEmpty) Float.NaN else r.top
    }
    .graphicsLayer { alpha = miniPlayerAlpha(sheet.fraction) }
    .draggable(
        state = sheet,
        orientation = Orientation.Vertical,
        onDragStopped = { velocity -> sheet.settle(velocity) },
    )

/**
 * The mini-player thumbnail's half of the cover's flight: it reports where the cover must land,
 * and hides itself while the sheet's cover is standing in for it — otherwise the last part of
 * every collapse would show two covers converging on one spot.
 */
fun Modifier.miniPlayerThumbOfSheet(sheet: PlayerSheetState): Modifier = this
    .onGloballyPositioned { sheet.thumbRect = sheet.rectInHost(it) }
    .graphicsLayer { alpha = if (sheet.coversThumb) 0f else 1f }

/** Fades one piece of the player's content (everything but the cover) as the sheet collapses. */
internal fun Modifier.sheetContentFade(sheet: PlayerSheetState): Modifier =
    graphicsLayer { alpha = sheetContentAlpha(sheet.fraction) }

/**
 * The slot the cover occupies, reporting its EXPANDED rect. Goes on a plain wrapper around the
 * cover, never on the cover itself: the cover carries [sheetArtFlight]'s graphics layer, and a
 * rect measured through that layer would include the very transform it is used to compute.
 *
 * Clears the rect when it leaves composition (the lyrics pane replaced the cover, or the sheet
 * went away), which is what gives the mini-player its own thumbnail back.
 */
@Composable
internal fun Modifier.sheetArtSlot(sheet: PlayerSheetState): Modifier {
    DisposableEffect(sheet) {
        onDispose { sheet.artRect = Rect.Zero }
    }
    return onPlaced { sheet.artRect = sheet.rectInSheet(it) }
}

/**
 * The cover's flight from its full-screen rect onto the mini-player's thumbnail, driven by the
 * sheet fraction — what replaced the nav-transition shared-element morph.
 *
 * Interpolated in HOST coordinates: at fraction `f` the cover should be drawn at
 * `lerp(expandedRect, thumbRect, f)`, while its layout box has been carried to
 * `expandedRect + (0, offsetPx)` by the sheet's translation. The layer maps the one onto the
 * other with a scale and a translation about the top-left corner. Linear in `f` because the spec
 * asks for exactly that ("by drag fraction"), and because the finger is what drives it: an eased
 * mapping would make the cover lag or lead the hand.
 *
 * The rounding travels too: the clip shape is in the layer's UNSCALED space, so the radius is
 * divided by the scale to land on [landingCorner] on screen — otherwise the 20 dp corner would
 * shrink with the cover to ~3 dp and pop to the thumbnail's 8 dp on the landing frame.
 *
 * Until the slot has reported a rect (the first frame of a drag that started on the mini-player,
 * which is when the sheet is composed) the cover is hidden rather than drawn at full size at the
 * bottom of the screen for that frame.
 */
internal fun Modifier.sheetArtFlight(
    sheet: PlayerSheetState,
    corner: Dp,
    landingCorner: Dp = MINI_PLAYER_THUMB_CORNER,
): Modifier = graphicsLayer {
    val from = sheet.artRect
    val to = sheet.thumbRect
    val f = sheet.fraction
    val cornerPx = corner.toPx()
    clip = true
    if (from.isEmpty) {
        shape = RoundedCornerShape(cornerPx)
        if (f > 0f) alpha = 0f
        return@graphicsLayer
    }
    if (to.isEmpty || f <= 0f) {
        shape = RoundedCornerShape(cornerPx)
        return@graphicsLayer
    }
    val landed = lerp(from, to, f)
    val sx = landed.width / from.width
    val sy = landed.height / from.height
    transformOrigin = TransformOrigin(0f, 0f)
    scaleX = sx
    scaleY = sy
    translationX = landed.left - from.left
    translationY = landed.top - (from.top + sheet.offsetPx)
    shape = RoundedCornerShape(lerp(cornerPx, landingCorner.toPx(), f) / sx)
}

/**
 * The now-playing sheet: the full player (or, one tap further in, full-screen lyrics) drawn
 * over the whole app, translated down by the sheet's fraction.
 *
 * Composed only while [PlayerSheetState.isPresent]. Collapsed and at rest there is no sheet,
 * only the mini-player — which is also why reopening always starts on the artwork and on the
 * player rather than on lyrics: the saveable state below goes with the composition.
 *
 * **Full-screen lyrics is a state in here, not a route** (spec §8). It was a destination entered
 * only from now-playing and left only back to it; with now-playing no longer a destination there
 * is nothing for it to sit on top of. A [rememberSaveableStateHolder] keeps the player's own
 * saveable state (its in-place lyrics pane) across the hop, the way the back stack used to.
 *
 * **Back** (spec §6): while expanded, the system back gesture pulls the sheet down by
 * [BACK_PEEK] as it is made, then collapses on commit or returns on cancel. On API < 34 there is
 * no progress, so it is a plain collapse. With full-screen lyrics up, back returns to the player
 * first — the later-registered [BackHandler] wins.
 */
@Composable
fun PlayerSheet(
    sheet: PlayerSheetState,
    vm: NowPlayingViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenSettings: () -> Unit,
) {
    // Nothing playing, nothing to show (spec §10): the sheet cannot be open over an empty queue,
    // and if the queue empties while it is open it goes away. A snap, because there is no cover
    // left to fly anywhere. Collected as a distinct Boolean, above the early return, so this
    // runs whether or not the sheet is composed — and does not recompose on every player event.
    val active by remember(vm) {
        vm.nowPlaying.map { it.isActive }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = vm.nowPlaying.value.isActive)
    LaunchedEffect(active) {
        if (!active) sheet.snapTo(SheetValue.Collapsed)
    }

    if (!sheet.isPresent) return
    val scope = rememberCoroutineScope()
    val reduced = rememberReducedMotion()
    var fullLyrics by rememberSaveable { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()

    PredictiveBackHandler(enabled = sheet.isExpanded && !fullLyrics) { progress ->
        var committed = false
        try {
            sheet.drag(MutatePriority.UserInput) {
                progress.collect { event -> sheet.peekForBack(event.progress) }
            }
            committed = true
        } finally {
            // Launched rather than run here, because this handler's own job is cancelled as soon
            // as `enabled` flips — and the collapse is what flips it. A settle run in place would
            // be cancelled by its own first line.
            scope.launch {
                sheet.animateTo(if (committed) SheetValue.Collapsed else SheetValue.Expanded)
            }
        }
    }
    BackHandler(enabled = fullLyrics) { fullLyrics = false }

    // Settings is reached from both lyrics surfaces. The sheet gets out of the way first — a snap,
    // since the Settings transition is about to cover the screen anyway — so the user lands on
    // Settings, and back from it lands on the app with the mini-player, not on a sheet that
    // Settings was drawn underneath.
    val openSettings = {
        scope.launch { sheet.snapTo(SheetValue.Collapsed) }
        onOpenSettings()
    }

    Box(
        Modifier
            .fillMaxSize()
            // Layer-phase translation: a drag moves the sheet without recomposing or even
            // re-placing it.
            .graphicsLayer { translationY = sheet.offsetPx }
            .draggable(
                state = sheet,
                orientation = Orientation.Vertical,
                // Full-screen lyrics is a reading surface with its own way back; it was never
                // swipe-dismissable as a route either.
                enabled = !fullLyrics,
                // A sheet that is still moving is caught on the DOWN, not after touch slop, so a
                // grab mid-settle stops it under the finger instead of letting it run on for 8 dp.
                startDragImmediately = sheet.isSettling,
                onDragStopped = { velocity -> sheet.settle(velocity) },
            ),
    ) {
        // The sheet's own coordinate space, measured INSIDE the translation above so the cover's
        // expanded rect never includes the drag. See sheetArtSlot.
        Box(
            Modifier
                .fillMaxSize()
                .onPlaced { sheet.sheetCoords = it },
        ) {
            AnimatedContent(
                targetState = fullLyrics,
                transitionSpec = {
                    if (reduced) {
                        fadeIn(snap()) togetherWith fadeOut(snap()) using null
                    } else {
                        fadeIn(Motion.gentle) togetherWith fadeOut(Motion.gentle) using null
                    }
                },
                label = "playerOrLyrics",
            ) { lyrics ->
                holder.SaveableStateProvider(lyrics) {
                    if (lyrics) {
                        LyricsScreen(
                            vm = vm,
                            onBack = { fullLyrics = false },
                            onOpenSettings = openSettings,
                        )
                    } else {
                        NowPlayingScreen(
                            vm = vm,
                            sheet = sheet,
                            onCollapse = { scope.launch { sheet.collapse() } },
                            onOpenLyrics = { fullLyrics = true },
                            onOpenSettings = openSettings,
                        )
                    }
                }
            }
        }

        // The scaffold's snackbar host is UNDER this sheet, so "Couldn't play …" — which a play
        // started from a list, and so opening this sheet, is exactly when it tends to appear —
        // would be hidden. A second host on the same state shows it here while the player's
        // backdrop is fully opaque; past that point the scaffold's own is visible through the
        // fading sheet and this one steps aside. Both hosts render the same SnackbarData, so the
        // hand-over is the same snackbar in two places for a frame, not two snackbars.
        val showSnackbar by remember(sheet) {
            derivedStateOf { sheet.fraction < CONTENT_FADE_START }
        }
        if (showSnackbar) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }
}
