// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.OpenInFull
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.kaislate.veldtplayer.playback.sleep.SleepTimerState
import com.kaislate.veldtplayer.ui.components.ArtBackdrop
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.scrimAtText
import com.kaislate.veldtplayer.ui.lyrics.LyricsContent
import com.kaislate.veldtplayer.ui.lyrics.LyricsVisibleWhile
import com.kaislate.veldtplayer.ui.lyrics.consumeVerticalDrags
import com.kaislate.veldtplayer.ui.lyrics.lyricsBackdrop
import com.kaislate.veldtplayer.ui.lyrics.lyricsRegion
import com.kaislate.veldtplayer.ui.lyrics.lyricsScrimFloor
import com.kaislate.veldtplayer.ui.lyrics.lyricsBackdropText
import com.kaislate.veldtplayer.ui.lyrics.rememberLyricsGround
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.LocalIsLightTheme
import com.kaislate.veldtplayer.ui.theme.backdropText
import com.kaislate.veldtplayer.ui.theme.BackdropMarks
import com.kaislate.veldtplayer.ui.theme.BackdropText
import com.kaislate.veldtplayer.ui.theme.backdropMarks
import com.kaislate.veldtplayer.ui.theme.whenEnabled
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette
import kotlinx.coroutines.delay

/** Fraction of the width the cover occupies — air on both sides, not a bleed. */
private const val ART_WIDTH = 0.82f

private val ART_CORNER = 20.dp
private val SCREEN_INSET = 24.dp
private val STACK_GAP = 20.dp
private val TRANSPORT_GAP = 8.dp
private val PLAY_BUTTON = 72.dp
private val PLAY_GLYPH = 44.dp


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
 *   could connect to anything they did. The lyrics pane passes through here too (the caller
 *   ORs it in): lyrics are something being READ, like the queue, and fading the lyrics toggle
 *   and the pane's expand button out from under a reader would strand them in a mode whose
 *   own controls had disappeared.
 */
internal fun ambientEligible(
    reduced: Boolean,
    touchExploration: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    sheetOpen: Boolean,
): Boolean = !reduced && !touchExploration && isActive && isPlaying && !sheetOpen

/**
 * Latches whether the chrome is faded ([faded]) at each pointer DOWN on this element, for
 * [FadedTapLatch.consume] to read at the click, and clears the latch when that gesture ENDS.
 * Never consumes anything: it observes, and takes nothing from the control under it.
 *
 * **Ordering, per pointer event** (passes run Initial → Main → Final across the whole hit path):
 *
 * - The DOWN is read on the Initial pass. The read is synchronous in the down's own dispatch, so
 *   it precedes anything the root's wake does — that only takes effect on a later frame.
 * - The UP reaches the control's `clickable` on the Main pass, and a real tap's `onClick` — hence
 *   [FadedTapLatch.consume] — runs synchronously right there (suspending pointer input resumes
 *   inside the dispatch).
 * - This then sees that same UP on the FINAL pass, strictly after Main, and calls
 *   [FadedTapLatch.gestureEnded]. For a tap that is a no-op (already consumed). For a gesture that
 *   never became a click — a partial drag the root's detector took over, a press dragged off the
 *   control, a cancellation — it clears the stale down-time value, so a later non-pointer
 *   activation (TalkBack double-tap, Enter, D-pad centre) falls back to the LIVE faded state.
 */
private fun Modifier.latchFadeAtDown(latch: FadedTapLatch, faded: () -> Boolean): Modifier =
    pointerInput(latch) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            latch.down(faded())
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
            } while (event.changes.any { it.pressed })
            latch.gestureEnded()
        }
    }

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
 * **It lives in a sheet, not on the back stack** — see [PlayerSheet] and [PlayerSheetState]. The
 * sheet owns the vertical drag (it wraps this screen), so this screen no longer detects a dismiss
 * gesture of its own; what it keeps is its content, and two hooks into the sheet's fraction:
 *
 * - **The cover flies onto the mini-player's thumbnail** as the sheet collapses. Its slot reports
 *   the expanded rect ([sheetArtSlot]) and the cover itself carries the layer that interpolates
 *   it onto the thumbnail ([sheetArtFlight]). That replaced a nav-transition shared-element morph
 *   whose two ends had to be handed over on exactly the same frame, and which, being a fixed
 *   420 ms transition, could be neither driven by the finger nor interrupted.
 * - **Everything else fades** almost at once ([sheetContentFade]: gone by 9% of the travel) —
 *   the backdrop included, so from there on only the cover crosses the live app, and the
 *   mini-player row fades in near the end where the cover lands. Opening mirrors it.
 *
 * Both are layer-phase reads of the fraction: a drag recomposes nothing here.
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
 *
 * **Lyrics and the cover's flight.** The lyrics pane takes the artwork's slot, and only the
 * ArtImage branch carries [sheetArtSlot]. With the pane up there is no cover to fly: the pane
 * fades with the rest of the content, and the mini-player's own thumbnail shows (the slot's
 * rect is cleared when it leaves composition, which is what tells the thumbnail it is not being
 * stood in for). Opening the sheet always lands on the artwork anyway: collapsed, the sheet is
 * not composed at all, and the saved `showLyrics` goes with it.
 */
@Composable
fun NowPlayingScreen(
    vm: NowPlayingViewModel,
    sheet: PlayerSheetState,
    onCollapse: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, never bare collectAsState: positionMs is a WhileSubscribed
    // 250ms poll and only stops when its collectors detach, so a backgrounded screen holding
    // a plain collector would keep the ticker running behind the user's back.
    val state by vm.nowPlaying.collectAsStateWithLifecycle()
    val position by vm.positionMs.collectAsStateWithLifecycle()
    val targetSeed by vm.seed.collectAsStateWithLifecycle()
    val isLight = LocalIsLightTheme.current
    val targetPalette = targetSeed.colors(isLight = isLight)
    val reduced = rememberReducedMotion()

    // Colours DRIFT to the new track instead of cutting (spec §6).
    val palette = rememberAnimatedPalette(targetPalette)

    // Solved against the ANIMATED palette.bg, not targetPalette.bg: the backdrop drifts to the
    // new track's colours over ~600ms (see rememberAnimatedPalette above), and solving against
    // the target would snap the text to its destination colour while the ground it sits on is
    // still mid-drift. This is pure arithmetic re-run every frame the animation touches, so the
    // text drifts along with the ground for free. scrimAtText(isLight), not
    // backdropScrim(isLight).top: the latter is the gradient's value at y=0 where no glyph
    // renders, and it is PER THEME because light's and dark's gradients are different shapes —
    // see scrimAtText's KDoc for the device-measured fix-round that made it so.
    val text = targetSeed.backdropText(palette.bg, scrimAtText(isLight), isLight)
    // Same ground, same reason — see BackdropMarks. Text was only half of what this surface
    // draws; the wave, the scrub track and the two toggles were still solved against `bg`.
    val marks = targetSeed.backdropMarks(palette.bg, scrimAtText(isLight), isLight)
    // The lyrics pane sits in the ARTWORK slot, above the title band scrimAtText describes, so
    // it draws a scrim floor lifting the weakest scrim under its lines to scrimAtText — and its
    // lines then use [text], solved there, exactly like the title. See lyricsScrimFloor. Only
    // the pane draws it, and only while it is shown: the artwork view is unchanged.
    val lyricsGround = rememberLyricsGround()

    var showQueue by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    // The service's sleep timer (spec §4). A timed one counts down on the elapsed-realtime clock,
    // re-read once a second while it runs and not at all otherwise; "end of this track" counts
    // down with the position this screen already polls.
    val sleep by vm.sleepTimer.collectAsStateWithLifecycle()
    val sleepNow by produceState(SystemClock.elapsedRealtime(), sleep) {
        while (sleep is SleepTimerState.Timed) {
            value = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val sleepRemaining = sleepRemainingMs(sleep, sleepNow, position, state.durationMs)
        ?.let(::formatSleepRemaining)
    // Lyrics in place of the artwork (spec §7). Saveable, so the pane is still up when the
    // user comes back from full-screen lyrics — the sheet keeps this screen's saveable state
    // across that hop (see PlayerSheet). A collapse takes the whole sheet out of composition,
    // so reopening now-playing always starts on the artwork, which is what the cover's flight
    // from the mini-player needs (see "Lyrics and the cover's flight" in the KDoc).
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    // The host below also guards on isActive, because this cannot run until after the frame
    // that dropped it. This is the other half: without it the flag would still read "open"
    // once the sheet had been taken away, and the sheet would spring back on its own the next
    // time something started playing. The lyrics pane is reset for the same reason: a new
    // queue starts on its artwork.
    LaunchedEffect(state.isActive) {
        if (!state.isActive) {
            showQueue = false
            showLyrics = false
            showSleep = false
        }
    }
    // Resolution runs only while the pane is actually on screen (spec §6). A DisposableEffect
    // underneath, so leaving composition — a collapse, or the hop to full-screen lyrics —
    // releases this surface's claim; see LyricsViewers for why it is a claim and not a flag.
    LyricsVisibleWhile(vm, visible = showLyrics && state.isActive)
    // A counter rather than a timestamp: it only ever has to differ from its previous value
    // to restart the idle timer, and a monotonic tick cannot be confused by a clock change.
    var lastTouchTick by remember { mutableIntStateOf(0) }
    var chromeVisible by remember { mutableStateOf(true) }

    val ambientArmed = ambientEligible(
        reduced = reduced,
        touchExploration = rememberTouchExploration(),
        isActive = state.isActive,
        isPlaying = state.isPlaying,
        // Review Focus 2: lyrics mode disarms the fade — see the sheetOpen clause. The sleep
        // sheet is a sheet like the queue's, for the same reason.
        sheetOpen = showQueue || showLyrics || showSleep,
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
    val chromeLiveState = remember(chromeAlpha) { derivedStateOf { chromeAlpha.value > 0f } }
    val chromeLive by chromeLiveState
    // One latch per control that "only wakes" while faded: the decision is taken at pointer DOWN,
    // before the wake that same down triggers can make the chrome read as live — see FadedTap.
    val artTapLatch = remember { FadedTapLatch() }
    val collapseTapLatch = remember { FadedTapLatch() }
    // The fade is one thing; taking the controls out of the accessibility and focus trees is
    // another, and only the second one can strand somebody. While a service is running the
    // chrome still fades to nothing on screen but is never withdrawn. See [chromeReachable].
    val chromeUsable = chromeReachable(chromeLive, rememberAccessibilityActive())

    Box(
        modifier
            .fillMaxSize()
            // The backdrop fills this box, so this box's coordinates ARE the gradient's.
            .lyricsBackdrop(lyricsGround)
            // Any touch at all wakes the chrome. Initial pass and never consumed: this must
            // not take the gesture away from the scrub bar, the transport or the sheet's
            // drag (on the PlayerSheet box around this one), only observe that one happened.
            // See the KDoc.
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
    ) {
        // fillMaxSize under a fillMaxSize Box, i.e. BOUNDED constraints. It has to be: the
        // backdrop's ArtImage draws its loading state with fillMaxSize, which collapses to
        // the minimum constraint under an unbounded parent — the backdrop would measure ~0
        // and then pop to full screen when the bitmap arrived.
        //
        // Fades with the rest of the content as the sheet collapses: the backdrop IS the
        // sheet's surface, so fading it is what lets the app and the mini-player show through
        // where the sheet is landing.
        ArtBackdrop(
            art = state.art,
            palette = palette,
            reducedMotion = reduced,
            modifier = Modifier
                .fillMaxSize()
                .sheetContentFade(sheet),
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
                // Reachable for a frame or two: the sheet collapses itself when the queue
                // empties, but that runs after the frame that emptied it. Saying so beats
                // rendering a blank screen with dead controls meanwhile.
                Text(
                    text = "Nothing playing",
                    style = MaterialTheme.typography.headlineSmall,
                    color = text.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.sheetContentFade(sheet),
                )
            } else {
                val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
                // One slot, two occupants: the pane is exactly the artwork's square, so
                // swapping them moves nothing else on the screen. `using null` (no size
                // transform) because the two are the same size, and a SizeTransform would also
                // clip the slot to its bounds.
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = {
                        if (reduced) {
                            fadeIn(snap()) togetherWith fadeOut(snap()) using null
                        } else {
                            fadeIn(Motion.gentle) togetherWith fadeOut(Motion.gentle) using null
                        }
                    },
                    contentAlignment = Alignment.Center,
                    label = "artOrLyrics",
                    modifier = Modifier
                        .fillMaxWidth(ART_WIDTH)
                        .aspectRatio(1f)
                        .lyricsRegion(lyricsGround),
                ) { lyricsShown ->
                    if (lyricsShown) {
                        // Solved at the title band's modelled ground; the pane's floor draws more
                        // scrim than that, as margin. See lyricsBackdropText.
                        val lyricsText = targetSeed.lyricsBackdropText(palette.bg, isLight)
                        // No sheetArtSlot on this branch, on purpose: the pane is not the cover,
                        // and flying it onto the thumbnail would shrink a block of text into the
                        // mini-player. It fades with the rest of the content instead, and the
                        // mini-player keeps its own thumbnail — see "Lyrics and the cover's
                        // flight" in the KDoc.
                        LyricsContent(
                            state = lyricsState,
                            positionMs = position,
                            text = lyricsText,
                            onSeek = vm::seekTo,
                            onOpenSettings = onOpenSettings,
                            footerAction = {
                                IconButton(onClick = onOpenLyrics) {
                                    Icon(
                                        Icons.Filled.OpenInFull,
                                        contentDescription = "Full-screen lyrics",
                                        tint = lyricsText.primary,
                                    )
                                }
                            },
                            // Review Focus 1: a vertical drag that starts on the pane stays in
                            // the pane. It scrolls the lyrics and never reaches the sheet's
                            // draggable; see consumeVerticalDrags for the mechanism.
                            modifier = Modifier
                                .fillMaxSize()
                                .sheetContentFade(sheet)
                                // The floor takes the artwork's rounded square, so the swap
                                // reads as the cover giving way to a tinted card of the same
                                // shape rather than a hard-edged block.
                                .clip(RoundedCornerShape(ART_CORNER))
                                .lyricsScrimFloor(lyricsGround, palette.bg, isLight)
                                .consumeVerticalDrags(),
                        )
                    } else {
                        // The slot measures, the cover flies: the expanded rect must be read on
                        // a node WITHOUT the flight's layer, or it would include the transform
                        // it is used to compute. See sheetArtSlot.
                        Box(Modifier.fillMaxSize().sheetArtSlot(sheet)) {
                            ArtImage(
                                art = state.art,
                                palette = palette,
                                initial = state.initial,
                                // The flight carries the clip (its rounding has to shrink with
                                // the cover onto the thumbnail's radius), so there is no separate
                                // clip here. The click comes after it: a tap lands on the cover
                                // wherever the cover is drawn.
                                modifier = Modifier
                                    .fillMaxSize()
                                    .sheetArtFlight(sheet, corner = ART_CORNER)
                                    // Faded chrome: the tap only wakes, exactly like the collapse
                                    // button — a tap aimed at a screen with no visible controls
                                    // means "come back", not "switch modes". Decided at the DOWN
                                    // (the latch), not at the click: see FadedTap.
                                    .latchFadeAtDown(artTapLatch) { !chromeLiveState.value }
                                    .clickable(
                                        onClickLabel = if (chromeLive) "Show lyrics" else "Show controls",
                                    ) {
                                        if (artTapLatch.consume(fadedNow = !chromeLive)) {
                                            lastTouchTick++
                                        } else {
                                            showLyrics = true
                                        }
                                    },
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .ambientChrome(chromeAlpha, chromeUsable)
                        .sheetContentFade(sheet),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = text.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        // Both already routed through DisplayNames by NowPlayingState —
                        // there is no second blank-tag rule anywhere in the UI.
                        text = "${state.artist} · ${state.album}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = text.secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                WaveScrubBar(
                    positionMs = position,
                    durationMs = state.durationMs,
                    palette = palette,
                    text = text,
                    marks = marks,
                    reducedMotion = reduced,
                    // The bar stays through the fade — it is part of the record — but it must
                    // not still be a SEEK target when it is the only live control left on a
                    // screen with no visible chrome. A tap on it then means "wake up", and it
                    // spans the full width right under the artwork, which is exactly where a
                    // user aims when they want the chrome back. Dragging survives. See
                    // WaveScrubBar's KDoc.
                    tapToSeek = chromeLive,
                    onSeek = vm::seekTo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .sheetContentFade(sheet),
                )

                Transport(
                    state = state,
                    text = text,
                    marks = marks,
                    interactive = chromeUsable,
                    onShuffle = { vm.setShuffle(!state.shuffle) },
                    onPrevious = vm::previous,
                    onToggle = vm::toggle,
                    onNext = vm::next,
                    onRepeat = vm::cycleRepeat,
                    modifier = Modifier
                        .ambientChrome(chromeAlpha, chromeUsable)
                        .sheetContentFade(sheet),
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
            // Decided at the DOWN, not here: by the click the wake from that same down has
            // already made the chrome read as live. See FadedTap.
            onClick = {
                if (collapseTapLatch.consume(fadedNow = !chromeLive)) lastTouchTick++ else onCollapse()
            },
            modifier = Modifier
                .latchFadeAtDown(collapseTapLatch) { !chromeLiveState.value }
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 8.dp)
                .ambientFade(chromeAlpha)
                .sheetContentFade(sheet),
        ) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                // Announced as what it will actually do when pressed, not as what it is
                // labelled the rest of the time — a screen reader has no other way to find
                // out that the screen is currently in ambient mode.
                contentDescription = if (collapseWakes) "Show controls" else "Collapse",
                tint = text.primary,
            )
        }

        // Docked opposite the collapse button rather than added to the transport, which is a
        // deliberate departure from the brief: that row is five controls arranged around one
        // big play button, and a sixth glyph on one side turns a symmetric object into a
        // lopsided one. The two corner affordances are the surface's chrome — a way out and
        // a way to what's next — and they read as a pair.
        //
        // The lyrics toggle joins the queue button in that corner (spec §7, "beside the queue
        // button") rather than the transport, for the same symmetry reason. It fades with the
        // rest of the chrome while the artwork is showing; in lyrics mode ambient mode is
        // disarmed, so it never fades out of a mode it is the way out of.
        if (state.isActive) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(end = 8.dp)
                    .ambientChrome(chromeAlpha, chromeUsable)
                    .sheetContentFade(sheet),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The sleep timer (spec §4) joins the corner for the reason the lyrics toggle did:
                // the transport row is a symmetric object. While a timer runs, the time left sits
                // beside the moon, in the solved text tone like the title.
                if (sleepRemaining != null) {
                    Text(
                        text = sleepRemaining,
                        style = MaterialTheme.typography.labelLarge,
                        color = text.primary,
                        // Announced by the button instead, as part of what it opens.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
                IconButton(
                    onClick = { showSleep = true },
                    enabled = chromeUsable,
                ) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = if (sleepRemaining == null) "Sleep timer"
                        else "Sleep timer, $sleepRemaining left",
                        tint = if (sleep != SleepTimerState.Off) marks.accent else text.primary,
                    )
                }
                IconButton(
                    onClick = { showLyrics = !showLyrics },
                    enabled = chromeUsable,
                ) {
                    Icon(
                        Icons.Filled.Lyrics,
                        // Announces the action, as the transport toggles announce their state:
                        // the tint alone would be invisible to a screen reader.
                        contentDescription = if (showLyrics) "Hide lyrics" else "Show lyrics",
                        tint = if (showLyrics) marks.accent else text.primary,
                    )
                }
                IconButton(
                    onClick = { showQueue = true },
                    enabled = chromeUsable,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = "Queue",
                        tint = text.primary,
                    )
                }
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

        // Guarded on isActive for the reason the queue sheet's host is.
        if (showSleep && state.isActive) {
            SleepTimerSheet(
                state = sleep,
                remaining = sleepRemaining,
                palette = palette,
                onSetMinutes = vm::setSleepTimer,
                onSetEndOfTrack = vm::setSleepTimerEndOfTrack,
                onExtend = vm::extendSleepTimer,
                onCancel = vm::cancelSleepTimer,
                onDismiss = { showSleep = false },
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
    text: BackdropText,
    marks: BackdropMarks,
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
                tint = if (state.shuffle) marks.accent else marks.quiet,
            )
        }
        IconButton(onClick = onPrevious, enabled = canPrevious) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Previous",
                tint = text.primary.whenEnabled(canPrevious),
            )
        }
        IconButton(onClick = onToggle, enabled = live, modifier = Modifier.size(PLAY_BUTTON)) {
            Icon(
                imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = text.primary.whenEnabled(live),
                modifier = Modifier.size(PLAY_GLYPH),
            )
        }
        IconButton(onClick = onNext, enabled = canNext) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = text.primary.whenEnabled(canNext),
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
                tint = if (state.repeat == RepeatMode.OFF) marks.quiet else marks.accent,
            )
        }
    }
}
