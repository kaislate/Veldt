// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.playback.NowPlayingState
import com.kaislate.veldtplayer.playback.RepeatMode
import com.kaislate.veldtplayer.ui.components.ArtBackdrop
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.sharedSongArt
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.SUBTITLE_ALPHA
import com.kaislate.veldtplayer.ui.theme.onBgFor
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette
import kotlinx.coroutines.delay

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

private const val INACTIVE_ALPHA = 0.5f

/** How long the surface must go untouched before the chrome fades away. */
private const val AMBIENT_DELAY_MS = 8_000L

/**
 * Whether ambient mode may engage right now — the whole "can this strand somebody?" question
 * in one pure predicate, so the answer is unit-testable instead of buried in a
 * `LaunchedEffect` on a screen that needs a device to run.
 *
 * Every clause is a way the fade would take something away that the user still needs:
 *
 * - [reduced] — the system animator scale is 0. The user has asked for no animation; a
 *   chrome fade is an animation, and one that also removes controls.
 * - [touchExploration] — TalkBack (or any touch-exploration service) is on. Ambient mode
 *   hides the transport on an IDLE TIMER, and a screen reader user's idle is not the same
 *   as a sighted user's: they are reading, not watching. Fading controls out from under a
 *   linear-navigation user is the one version of this feature that genuinely strands
 *   somebody, so it simply does not run.
 * - [isActive] — nothing is playing, so the surface reads "Nothing playing" and the only
 *   chrome on it is the collapse button. Fading THAT leaves an empty screen whose only exit
 *   is an undiscoverable downward swipe.
 * - [isPlaying] — paused (or stalled: `isStalled` implies not playing, so this covers it).
 *   Ambient mode is for a record left playing; hiding the play button from someone who
 *   stopped to look at the artwork just costs them a wake-up tap before every resume.
 * - [sheetOpen] — the queue sheet is up and taking the touches, so the idle timer would run
 *   to completion behind it and the chrome would be gone on dismiss for no reason the user
 *   could connect to anything they did.
 */
internal fun ambientEligible(
    reduced: Boolean,
    touchExploration: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    sheetOpen: Boolean,
): Boolean = !reduced && !touchExploration && isActive && isPlaying && !sheetOpen

/**
 * Whether the chrome is still REACHABLE — the second half of ambient mode, kept separate from
 * [ambientEligible] because it answers a different question and answers it later.
 *
 * [ambientEligible] decides whether the fade may START. This decides, once it has finished,
 * whether the faded chrome may also be WITHDRAWN — dropped out of the accessibility tree by
 * `clearAndSetSemantics` and out of the focus tree by `enabled = false`. Those two are what
 * turn a visual fade into a disappearance, and they are the only part that can strand anyone.
 *
 * - [chromeLive] — the ordinary answer. The chrome is visible to some degree, so it is
 *   reachable by everything, and the withdrawal has not happened yet.
 * - [accessibilityActive] — `AccessibilityManager.isEnabled`: SOME service is driving this
 *   device. Ambient mode's wake signal is a pointer down, and Switch Access, Voice Access and
 *   scanning services never produce one; the key-event and focus-arrival wakes cover a D-pad
 *   and a keyboard but not a service that activates by accessibility action. Rather than guess
 *   which services can wake it, nothing is taken away from any of them: the chrome still fades
 *   to nothing on screen — the signature stays intact, for everyone, including these users —
 *   but every control keeps its place in both trees, so it can always be found and pressed.
 *
 * Deliberately NOT folded into [ambientEligible] as a sixth disqualifier. `isEnabled` is true
 * for any enabled service at all, including ones with no stake in this (a magnifier, a
 * password-reveal helper, a third-party keyboard's service), and disqualifying on it would
 * switch the fade OFF outright for exactly those users instead of merely keeping their
 * controls. The eligibility gate keeps the one service that must not see this feature at all —
 * touch exploration, whose users navigate linearly and whose idle is reading, not watching.
 *
 * The cost is accepted rather than hidden: with a service running, a faded transport is still
 * PRESSABLE, so a pointer tap aimed at the invisible pause button pauses rather than falling
 * through to the root's wake handler. A control that is present but invisible for one press is
 * strictly better than a control that has ceased to exist with no way to bring it back.
 */
internal fun chromeReachable(chromeLive: Boolean, accessibilityActive: Boolean): Boolean =
    chromeLive || accessibilityActive

/**
 * The ambient fade itself, and nothing else.
 *
 * [alpha] arrives as a `State` and is unwrapped inside the `graphicsLayer` block rather than
 * by the caller, so the per-frame snapshot read lands in the layer phase and the fade costs
 * no recomposition at all.
 *
 * Used bare by exactly one control — the collapse button, which fades but never leaves either
 * tree. See its call site for why.
 */
private fun Modifier.ambientFade(alpha: State<Float>): Modifier =
    this.graphicsLayer { this.alpha = alpha.value }

/**
 * Ambient fade for one piece of chrome, plus the withdrawal that goes with it.
 *
 * Once it is fully gone the chrome also leaves the accessibility tree. Alpha is a DRAW
 * property: a fully transparent transport is still a row of focusable, clickable buttons, so
 * without this a sighted user could tab focus onto an invisible pause button.
 *
 * The pointer half of the same problem is handled at the call sites, which pass
 * `enabled = false` on the same [live] signal: a disabled `clickable` does not consume the
 * down, so a tap aimed at a button nobody can see falls through to the root Box and wakes the
 * chrome instead of silently pausing the music.
 *
 * **[live] is [chromeReachable], NOT the raw visibility.** `enabled = false` also takes a
 * control out of the FOCUS tree, so between the two of them this modifier makes a control
 * unreachable by any input method that is not a pointer — and the pointer is precisely what a
 * Switch Access or Voice Access user does not have. While any accessibility service is running
 * the withdrawal is therefore suppressed outright: the fade still runs, so the screen still
 * becomes the record, but nothing leaves either tree. See [chromeReachable] for why that is a
 * separate predicate from [ambientEligible] and not a sixth clause of it.
 */
private fun Modifier.ambientChrome(alpha: State<Float>, live: Boolean): Modifier {
    val faded = ambientFade(alpha)
    return if (live) faded else faded.clearAndSetSemantics { }
}

/**
 * True while a touch-exploration service (TalkBack et al) is driving the screen.
 *
 * Observed rather than read once: the setting is a quick-settings tile and a three-finger
 * gesture away, so a value sampled at first composition would be stale for exactly the user
 * who most needs it to be right.
 */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on ->
            enabled = on
        }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * True while ANY accessibility service is enabled — the signal [chromeReachable] gates on.
 *
 * A SECOND observer next to [rememberTouchExploration] rather than a widened one, because the
 * two questions have different answers and different listeners. `isEnabled` and
 * `isTouchExplorationEnabled` are separate properties with separate change callbacks, and
 * reading `isEnabled` while subscribed only to `TouchExplorationStateChangeListener` would
 * sample it once at composition and never hear about it again — stale for exactly the user it
 * exists to protect, who may well switch their service on while this screen is already open
 * and already faded. Hence `add/removeAccessibilityStateChangeListener`, its own listener for
 * its own property.
 */
@Composable
private fun rememberAccessibilityActive(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(AccessibilityManager::class.java)
    }
    var active by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.AccessibilityStateChangeListener { on -> active = on }
        manager?.addAccessibilityStateChangeListener(listener)
        onDispose { manager?.removeAccessibilityStateChangeListener(listener) }
    }
    return active
}

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
 *
 * **Ambient mode.** After [AMBIENT_DELAY_MS] untouched the chrome fades out and leaves the
 * artwork, the wave and the drifting backdrop — the screen stops being a control panel and
 * becomes the record. Every way that would turn into a trap rather than a mood is enumerated
 * in [ambientEligible], and the fade itself is [Motion.gentle] like every other chrome fade
 * in the app. Waking it is deliberately not a tap gesture: a down event is watched on the
 * INITIAL pass and never consumed, so a touch anywhere — including one a transport button or
 * the scrub bar goes on to handle — restores the chrome. `detectTapGestures` would have seen
 * neither, because it waits for an unconsumed down on the MAIN pass and those children get
 * there first.
 *
 * **Waking it is not only a pointer, either, and that is not a nicety.** A pointer-only wake
 * signal on a timer that also sets `enabled = false` is a trap with no door in it for anyone
 * who does not use a pointer: `enabled = false` removes a control from the focus tree just as
 * `clearAndSetSemantics` removes it from the accessibility tree, so eight seconds after a
 * Switch Access or keyboard user arrives, every control on the screen stops existing for them
 * and nothing they can do brings it back. Two mechanisms answer that, and they are chosen to
 * overlap rather than to divide the space:
 *
 * - **Any key event bumps the idle clock**, watched at the root and never consumed. That is a
 *   D-pad, an external keyboard, a remote — for whom moving focus IS a key press, so ordinary
 *   navigation counts as activity exactly the way ordinary touching does. Focus ENTERING the
 *   screen counts too, for a service that moves focus without a key event.
 * - **The collapse button never fades out of either tree**, so there is always one control to
 *   reach. Switch Access is the case the first mechanism does not cover: it scans by
 *   accessibility focus and activates by action, and produces no key event at any point.
 * - **And while any service is enabled at all, NOTHING is withdrawn** — not the transport, not
 *   the queue button, not the titles. The two mechanisms above are each a door out of a room
 *   that has been emptied; this one declines to empty the room. It is the load-bearing
 *   guarantee and the other two are the belt to its braces: whatever the service is and
 *   however it drives the screen, every control it could reach a second ago it can still
 *   reach. The fade itself is untouched, so the aesthetic is not spent on it. See
 *   [chromeReachable].
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

    var showQueue by remember { mutableStateOf(false) }
    // The host below also guards on isActive, because this cannot run until after the frame
    // that dropped it. This is the other half: without it the flag would still read "open"
    // once the sheet had been taken away, and the sheet would spring back on its own the next
    // time something started playing.
    LaunchedEffect(state.isActive) { if (!state.isActive) showQueue = false }
    // A counter rather than a timestamp: it only ever has to differ from its previous value
    // to restart the idle timer, and a monotonic tick cannot be confused by a clock change.
    var lastTouchTick by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }

    val ambientArmed = ambientEligible(
        reduced = reduced,
        touchExploration = rememberTouchExploration(),
        isActive = state.isActive,
        isPlaying = state.isPlaying,
        sheetOpen = showQueue,
    )
    // Keyed on the tick AND on eligibility, so both a touch and anything that disarms ambient
    // mode (a pause, the sheet opening, TalkBack coming on) bring the chrome straight back.
    LaunchedEffect(lastTouchTick, ambientArmed) {
        chromeVisible = true
        if (ambientArmed) {
            delay(AMBIENT_DELAY_MS)
            chromeVisible = false
        }
    }
    // Held as a State and never unwrapped here, deliberately. Reading the animating float in
    // COMPOSITION would invalidate this whole function once per frame for the length of every
    // fade — and this function composes the backdrop, the wave and the full-size art. The
    // value is unwrapped inside a `graphicsLayer` block instead, which defers the snapshot
    // read to the layer phase; see [ambientChrome].
    val chromeAlpha = animateFloatAsState(
        targetValue = if (chromeVisible) 1f else 0f,
        animationSpec = Motion.gentle,
        label = "ambientChrome",
    )
    // Interactive for as long as it is visible AT ALL, so the hand-off happens at the END of
    // the fade rather than at the start of it — a control the user can still see is a control
    // that still works. `derivedStateOf` for the same reason as above: this is a boolean that
    // flips twice per fade, and without it the >0f comparison would be a per-frame read.
    val chromeLive by remember(chromeAlpha) { derivedStateOf { chromeAlpha.value > 0f } }
    // The fade is one thing; taking the controls out of the accessibility and focus trees is
    // another, and only the second one can strand somebody. While a service is running the
    // chrome still fades to nothing on screen but is never withdrawn. See [chromeReachable].
    val chromeUsable = chromeReachable(chromeLive, rememberAccessibilityActive())

    Box(
        modifier
            .fillMaxSize()
            // Any touch at all wakes the chrome. Initial pass and never consumed: this must
            // not take the gesture away from the scrub bar, the transport or the drag
            // detector below, only observe that one happened. See the KDoc.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    lastTouchTick++
                    // And again when the last finger LIFTS, so the idle clock starts from the
                    // end of the gesture rather than its beginning. Without this, a slow scrub
                    // across a long track fades the chrome out from under a finger that is
                    // still on the screen — idle is when nobody is touching it, not 8 seconds
                    // after somebody started.
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    lastTouchTick++
                }
            }
            // The non-pointer half of the same wake signal. Key events are delivered to the
            // focused node and bubble UP its parent chain, so this sees every press made
            // anywhere on the screen — a D-pad moving focus between transport buttons
            // included, which is what "activity" looks like when there is no finger.
            // Returns false always: observed, never consumed, exactly like the down above.
            .onKeyEvent {
                lastTouchTick++
                false
            }
            // And focus ARRIVING, for a service that moves focus by action rather than by key
            // event. Deliberately only the rising edge: focus is also lost when the fade
            // disables the transport out from under it, and treating THAT as activity would
            // wake the chrome every eight seconds forever.
            .onFocusChanged { if (it.hasFocus) lastTouchTick++ }
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

                Column(
                    modifier = Modifier.ambientChrome(chromeAlpha, chromeUsable),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                    // The bar stays through the fade — it is part of the record — but it must
                    // not still be a SEEK target when it is the only live control left on a
                    // screen with no visible chrome. A tap on it then means "wake up", and it
                    // spans the full width right under the artwork, which is exactly where a
                    // user aims when they want the chrome back. Dragging survives. See
                    // WaveScrubBar's KDoc.
                    tapToSeek = chromeLive,
                    onSeek = vm::seekTo,
                    modifier = Modifier.fillMaxWidth(),
                )

                Transport(
                    state = state,
                    palette = palette,
                    interactive = chromeUsable,
                    onShuffle = { vm.setShuffle(!state.shuffle) },
                    onPrevious = vm::previous,
                    onToggle = vm::toggle,
                    onNext = vm::next,
                    onRepeat = vm::cycleRepeat,
                    modifier = Modifier.ambientChrome(chromeAlpha, chromeUsable),
                )
            }
        }

        // Docked to the corner rather than placed in the centred stack above: a way back
        // belongs at the edge of the surface, not in the middle of the artwork.
        //
        // A VISIBLE affordance, not only the drag. A downward swipe is undiscoverable, and
        // — the reason this is not a preference — it is unreachable with TalkBack on, which
        // would leave the screen a one-way trip for a screen-reader user.
        //
        // THE ONE CONTROL AMBIENT MODE NEVER WITHDRAWS, UNCONDITIONALLY. It fades with
        // everything else, but it keeps `enabled = true` and so keeps its place in both the
        // accessibility tree and the focus tree — with no service running, and therefore no
        // chromeReachable reprieve, every other control on this screen is gone from both trees
        // while the chrome is down, so a sighted keyboard user eight seconds in would otherwise
        // have nothing left to focus. The paragraph two comments up claims this button is why
        // the screen is never a one-way trip; ambient mode taking it away would have made that
        // claim false, and taken the transport with it.
        //
        // Faded, it wakes rather than collapses. That keeps the rule the fade is built on —
        // a control nobody can see must not fire — and it is the same two-step every sighted
        // user already gets from ambient mode: the first press restores the chrome, the second
        // does the thing. It is also strictly better than the alternative reading of "one way
        // out survives", which would have been an exit and nothing else: this hands back the
        // WHOLE screen, transport included, rather than only the door.
        val collapseWakes = !chromeLive
        IconButton(
            onClick = { if (collapseWakes) lastTouchTick++ else onCollapse() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 8.dp)
                .ambientFade(chromeAlpha),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                // Announced as what it will actually do when pressed, not as what it is
                // labelled the rest of the time — a screen reader has no other way to find
                // out that the screen is currently in ambient mode.
                contentDescription = if (collapseWakes) "Show controls" else "Collapse",
                tint = palette.onBg,
            )
        }

        // Docked opposite the collapse button rather than added to the transport, which is a
        // deliberate departure from the brief: that row is five controls arranged around one
        // big play button, and a sixth glyph on one side turns a symmetric object into a
        // lopsided one. The two corner affordances are the surface's chrome — a way out and
        // a way to what's next — and they read as a pair.
        if (state.isActive) {
            IconButton(
                onClick = { showQueue = true },
                enabled = chromeUsable,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(end = 8.dp)
                    .ambientChrome(chromeAlpha, chromeUsable),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Queue",
                    tint = palette.onBg,
                )
            }
        }

        // `state.isActive` as well as the flag, and not belt-and-braces: the button that sets
        // showQueue is inside the isActive branch but this host is not, so the two can come
        // apart while the sheet is OPEN. `PlaybackConnection.publish()` resolves the current
        // song by indexing its queue and can come back null, which drops nowPlaying to EMPTY —
        // and the sheet would then be a list with no highlighted row, still offering every row
        // as a jump, because `canJump` only greys out on `isStalled` and isStalled is itself
        // qualified on isActive. Harmless further down (skipToQueueIndex bounds-checks) and
        // fixed here, where the state it describes actually is.
        if (showQueue && state.isActive) {
            // collectAsStateWithLifecycle, and collected only while the sheet is up: nothing
            // else on this screen reads the queue, so there is no reason to hold a collector
            // on it for the whole life of the surface.
            val queue by vm.queue.collectAsStateWithLifecycle()
            QueueSheet(
                songs = queue,
                currentSongId = state.songId,
                palette = palette,
                // Same bound the transport greys itself out on: once the error skip-on has
                // stopped, seekTo cannot restart an IDLE player and every row would be a
                // control that silently does nothing. See QueueSheet's KDoc.
                canJump = !state.isStalled,
                onJump = { index ->
                    vm.skipToQueueIndex(index)
                    showQueue = false
                },
                onDismiss = { showQueue = false },
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
 * survive that one — they set player fields and take effect on the next real playback
 * regardless.
 *
 * [interactive] is the other reason a control here can be dead, and it takes ALL FIVE with
 * it including the two toggles: it is false only while ambient mode has faded this row to
 * nothing AND no accessibility service is running ([chromeReachable]), and an invisible
 * control must not be pressable. A disabled `clickable` does not consume the down, so the tap
 * falls through to the root Box and wakes the chrome — which is what the user aiming at a
 * button they cannot see actually wants. With a service running the row stays enabled instead,
 * because `enabled = false` is also what removes it from the FOCUS tree, and a row that has
 * left both trees cannot be reached again by anyone who does not have a pointer.
 */
@Composable
private fun Transport(
    state: NowPlayingState,
    palette: DominantColors,
    interactive: Boolean,
    onShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = !state.isStalled && interactive
    val canPrevious = state.hasPrevious && live
    val canNext = state.hasNext && live

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TRANSPORT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onShuffle, enabled = interactive) {
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
        IconButton(onClick = onRepeat, enabled = interactive) {
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
