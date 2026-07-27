package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.ui.components.drawWave
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.VeldtText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs

/**
 * The signature surface: Veldt Wisp's wave, at full-screen scale, as the seek control.
 *
 * P1.3 renders exactly ONE style — "wisptrail" with consume on (global constraint 7).
 * The other renderers are present but dormant until P1.5 adds the picker.
 *
 * [positionMs] is the transport's own position and drives the wave whenever the user is
 * not touching the bar; during a drag the bar shows the finger instead, so the playhead
 * never fights a position tick arriving mid-gesture.
 *
 * [tapToSeek] is false while now-playing's ambient mode has faded the chrome away. The bar
 * itself deliberately stays — it is part of the record, not part of the control panel — but it
 * is then the ONLY live control on a screen whose every other control has gone, and it spans
 * the full width directly under the artwork. A user tapping the middle of the screen to bring
 * the chrome back is not asking to jump the playhead across the track, and losing your place in
 * a song is not an undoable mistake. Dragging survives, because a deliberate horizontal sweep
 * across a bar you cannot see is nobody's idea of "wake the screen up".
 *
 * Withheld by RE-KEYING the tap detector rather than by dropping it from the modifier chain:
 * the wake happens on the same down that would start the tap, so the flag flips one frame INTO
 * the gesture. Re-keying cancels that in-flight tap (correct — the finger went down on a faded
 * screen), and leaves the chain's shape, and therefore the drag detector's node and its
 * `dragFraction` sentinel, untouched. Removing an element instead would re-create every element
 * after it and could strand a drag mid-gesture.
 */
@Composable
fun WaveScrubBar(
    positionMs: Long,
    durationMs: Long,
    palette: DominantColors,
    reducedMotion: Boolean,
    tapToSeek: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val wavePhase = rememberWavePhase(reducedMotion)

    // -1f means "not dragging". A nullable Float would box on every drag frame.
    //
    // Keyed on durationMs to match the gesture detectors below. When that key changes the
    // detector's coroutine is cancelled outright and awaitEachGesture rethrows, so NEITHER
    // onDragEnd NOR onDragCancel runs — an unkeyed remember would strand dragFraction >= 0
    // and leave the bar permanently "dragging": playhead and readout frozen, thumb stuck at
    // the enlarged radius, never following the transport again. This is not an exotic case;
    // NowPlayingState swaps the MediaStore duration for the player-reported one shortly
    // after a track starts, with no track change, so resting a finger on the bar early in a
    // song is enough to trigger it. Re-keying resets the sentinel in the same recomposition
    // that re-arms the detectors.
    var dragFraction by remember(durationMs) { mutableFloatStateOf(NOT_DRAGGING) }
    val isDragging = dragFraction >= 0f

    // THE SEEK LATCH. The position ticker resumes before the seek has landed, so for one
    // 250ms tick the transport still reports the PRE-seek position — and without this the
    // playhead visibly snaps back there the instant the finger lifts, then jumps forward
    // again. Holding the released position until the transport catches up removes the whole
    // round trip from view. Keyed on durationMs for the same reason dragFraction is: a
    // duration change cancels the gesture detectors mid-gesture, and a stranded latch would
    // freeze the playhead permanently.
    var latchFraction by remember(durationMs) { mutableFloatStateOf(NOT_LATCHED) }
    val isLatched = latchFraction >= 0f

    // A track with no duration cannot be seeked, and must not look like it can: the
    // gestures below are attached only when there is something to seek to, so the
    // playhead can never be dragged somewhere the transport will refuse to go.
    val seekable = durationMs > 0L

    val liveFraction =
        if (seekable) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    // Released the moment the transport's own position reaches the seek target, or after
    // LATCH_TIMEOUT_MS if it never does — a seek can be refused outright (a disconnected
    // controller parks the command), and a latch with no timeout would leave the playhead
    // frozen at a position nothing is playing.
    val livePositionMs = rememberUpdatedState(positionMs)
    LaunchedEffect(latchFraction, durationMs) {
        if (latchFraction < 0f) return@LaunchedEffect
        val targetMs = latchFraction.toPositionMs(durationMs)
        withTimeoutOrNull(LATCH_TIMEOUT_MS) {
            snapshotFlow { livePositionMs.value }
                .first { abs(it - targetMs) <= LATCH_TOLERANCE_MS }
        }
        latchFraction = NOT_LATCHED
    }

    // The fraction the bar DRAWS, as an Animatable rather than the raw target, for exactly
    // one moment: the release of the latch. A seek does not always land where it was asked
    // to — a VBR file with no seek table resolves to the nearest sync frame — so the
    // playhead can have real distance to cover once the hold ends. Motion.settle is the spec
    // written for this, and it is the ONLY transition that animates: everything else snaps,
    // so the playhead never trails the transport during ordinary playback and a track change
    // does not sweep it back across the whole bar.
    //
    // Seeded from liveFraction, NOT 0f. This is re-created whenever durationMs changes, and
    // NowPlayingState swaps the MediaStore duration for the player-reported one a second or
    // two into every track — the same ordinary event documented above. Seeding at zero would
    // park the playhead at the far left for the frame before the driver below catches up.
    val playhead = remember(durationMs) { Animatable(liveFraction) }

    /** Where the playhead is HEADING — the transport's truth, unsmoothed. */
    val targetFraction = when {
        isDragging -> dragFraction
        isLatched -> latchFraction
        else -> liveFraction
    }
    val target = rememberUpdatedState(targetFraction)
    val held = rememberUpdatedState(isDragging || isLatched)
    LaunchedEffect(playhead) {
        var wasHeld = false
        snapshotFlow { target.value to held.value }.collect { (value, hold) ->
            if (wasHeld && !hold) playhead.animateTo(value, Motion.settle) else playhead.snapTo(value)
            wasHeld = hold
        }
    }

    // As State, never unwrapped here. Reading an Animatable in COMPOSITION recomposes this
    // whole function on every frame of the settle, re-running formatTime twice a frame — the
    // exact cost rememberWavePhase's KDoc exists to prevent, reintroduced one animation over.
    // The Canvas reads it in the draw phase instead.
    val playheadFraction = playhead.asState()

    /** What the readout says and what TalkBack reports — the HELD time, never the animation. */
    val readoutMs = when {
        isDragging -> dragFraction.toPositionMs(durationMs)
        isLatched -> latchFraction.toPositionMs(durationMs)
        else -> positionMs
    }

    // Grows under the finger. Selected from the motion vocabulary rather than hard-cut
    // between two radii, which on the one element the user is actually touching reads
    // as a glitch rather than as feedback.
    //
    // Held as State, not unwrapped with `by`, so the read below happens in the draw phase.
    // Under reduced motion the draw reads the target directly and this animation is never
    // observed — so it invalidates nothing, and Motion.snappy's overshoot (0.55 damping)
    // cannot spring on a user who asked for no animations.
    val targetThumbRadiusDp = if (isDragging) THUMB_RADIUS_DRAGGING_DP else THUMB_RADIUS_DP
    val thumbRadiusDp = animateFloatAsState(
        targetValue = targetThumbRadiusDp,
        animationSpec = Motion.snappy,
        label = "scrubThumbRadius",
    )

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CANVAS_HEIGHT_DP.dp)
                // Semantics are not focus. The accessibility node below makes this bar
                // reachable by anything that walks the a11y tree — TalkBack, Switch Access —
                // but Compose's D-pad and keyboard traversal is driven by the FOCUS tree, and
                // a Canvas is not focusable by default. Without this one modifier the app's
                // signature control is the only thing on the screen a D-pad or an external
                // keyboard cannot reach at all: every transport button is an IconButton and
                // brings its own focus target, so tabbing would step straight past the seek
                // bar as if it were decoration.
                .focusable()
                // TalkBack saw a bare Canvas: the app's signature control was an unlabelled
                // blank that could not be seeked at all. There is no Role for a seek bar in
                // Compose's set — the platform mapping comes from the PAIR below, which the
                // accessibility bridge turns into an android.widget.SeekBar node with an
                // adjustable value, exactly as Material's own Slider is announced.
                .semantics {
                    contentDescription = SCRUB_LABEL
                    stateDescription = "${formatTime(readoutMs)} of ${formatTime(durationMs)}"
                    // targetFraction, not the animated one: the two would disagree for the
                    // length of a settle, and stateDescription right above is the target.
                    progressBarRangeInfo = ProgressBarRangeInfo(targetFraction, 0f..1f)
                    if (seekable) {
                        setProgress { value ->
                            val f = value.coerceIn(0f, 1f)
                            latchFraction = f
                            onSeek(f.toPositionMs(durationMs))
                            true
                        }
                    } else {
                        // Same honesty as withholding the gestures: a track with no known
                        // duration is announced as a control that cannot be adjusted.
                        disabled()
                    }
                }
                .then(
                    if (!seekable) Modifier else Modifier
                        .pointerInput(durationMs, tapToSeek) {
                            // Not attached at all while the chrome is faded, rather than
                            // attached-and-ignoring: an unattached detector does not consume
                            // the down either, so the tap stays purely a wake gesture.
                            if (!tapToSeek) return@pointerInput
                            detectTapGestures { offset ->
                                // Latched like a drag release, not just seeked: a tap has
                                // the same pre-seek tick to ride out.
                                val f = offset.x.fractionOf(size.width)
                                latchFraction = f
                                onSeek(f.toPositionMs(durationMs))
                            }
                        }
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    dragFraction = offset.x.fractionOf(size.width)
                                },
                                onDragEnd = {
                                    // Guarded because a cancel can land between the two.
                                    if (dragFraction >= 0f) {
                                        latchFraction = dragFraction
                                        onSeek(dragFraction.toPositionMs(durationMs))
                                    }
                                    dragFraction = NOT_DRAGGING
                                },
                                onDragCancel = { dragFraction = NOT_DRAGGING },
                            ) { change, _ ->
                                // Clamped, so dragging past either end parks the playhead
                                // at that end instead of running off the canvas.
                                dragFraction = change.position.x.fractionOf(size.width)
                            }
                        }
                )
        ) {
            val baseY = size.height - BASELINE_FROM_BOTTOM_DP.dp.toPx()
            // Read directly while dragging rather than through the Animatable, so the
            // playhead tracks the finger with no coroutine hop in between — including if a
            // drag begins during a settle, which the sequential collector above would
            // otherwise not see until the settle ends.
            val fraction =
                if (dragFraction >= 0f) dragFraction else playheadFraction.value
            val playheadX = size.width * fraction

            // Unplayed remainder: a quiet track line the wave grows along.
            drawLine(
                color = palette.onBg.copy(alpha = TRACK_ALPHA),
                start = Offset(playheadX, baseY),
                end = Offset(size.width, baseY),
                strokeWidth = TRACK_STROKE_DP.dp.toPx(),
                cap = StrokeCap.Round,
            )

            if (playheadX > 1f) {
                drawWave(
                    style = WAVE_STYLE,
                    color = palette.accent,
                    ampPx = AMPLITUDE_DP.dp.toPx(),
                    // Read here, in the draw phase, never in composition. See rememberWavePhase.
                    phase = wavePhase.value,
                    baseY = baseY,
                    width = playheadX,
                    vibrant = palette.waveColors.isNotEmpty(),
                    waveColors = palette.waveColors,
                    taperStartPx = 0f,
                    // Taper into the playhead so nothing cliffs above the thumb.
                    taperEndPx = TAPER_END_DP.dp.toPx(),
                    consume = true,
                )
            }

            // Playhead LAST so it always sits on top of the wave.
            drawCircle(
                color = palette.onBg,
                radius = (if (reducedMotion) targetThumbRadiusDp else thumbRadiusDp.value)
                    .dp.toPx(),
                center = Offset(playheadX, baseY),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = READOUT_INSET_DP.dp)
                // Hidden from accessibility, not because it is decoration but because the
                // bar above already announces both times as its state — leaving these in
                // makes TalkBack read the same two numbers again as unlabelled stray text.
                .clearAndSetSemantics { },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(readoutMs),
                style = VeldtText.numeric,
                color = palette.onBg.copy(alpha = READOUT_ALPHA),
            )
            Text(
                formatTime(durationMs),
                style = VeldtText.numeric,
                color = palette.onBg.copy(alpha = READOUT_ALPHA),
            )
        }
    }
}

/**
 * Touch x as a 0..1 position along a bar of [width], clamped.
 *
 * Clamping here rather than at each call site is what makes "drag past the end" and
 * "lift the finger outside the bar" non-events: a horizontal drag keeps reporting
 * positions after the pointer leaves the canvas, and they arrive negative or past
 * [width]. Guards against a zero-width canvas, which would otherwise divide by zero
 * and hand NaN to the wave math.
 */
private fun Float.fractionOf(width: Int): Float =
    if (width <= 0) 0f else (this / width).coerceIn(0f, 1f)

/** Named so it never shadows the built-in Float.toLong(). */
private fun Float.toPositionMs(durationMs: Long): Long = (this * durationMs).toLong()

/** m:ss, or h:mm:ss past an hour. */
internal fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * The seamless loop: phase sweeps 0 -> 20π on [Motion.wavePhase]'s 26s linear cycle.
 * Every renderer's internal rates are chosen against that pairing, which is why both
 * halves live together in [Motion]. Frozen at [PHASE_REST] under reduced motion, which
 * leaves the wave drawn but still rather than removing it.
 *
 * Returns the `State` rather than the `Float`, exactly as `ArtBackdrop.rememberDrift`
 * does and for the same reason. A composable that READS an animated float recomposes on
 * every frame of it — and this loop never ends, so [WaveScrubBar] would recompose for as
 * long as the surface is visible, re-running `formatTime` twice a frame (two String
 * allocations for text that changes once a second) and rebuilding the modifier chain.
 * Handing the state down lets the `Canvas` read it in the draw phase, so the loop costs
 * no recomposition at all.
 */
@Composable
private fun rememberWavePhase(reducedMotion: Boolean): State<Float> {
    if (reducedMotion) return remember { mutableFloatStateOf(PHASE_REST) }
    val transition = rememberInfiniteTransition(label = "wavePhase")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = Motion.WAVE_PHASE_TURNS * PI.toFloat(),
        animationSpec = Motion.wavePhase,
        label = "wavePhaseValue",
    )
}

/** Phase the wave rests at when animations are off — the start of the loop. */
private const val PHASE_REST = 0f

private const val NOT_DRAGGING = -1f
private const val NOT_LATCHED = -1f

/**
 * How close the transport has to get to the seek target before the latch lets go. Three
 * ticks' worth: the ticker's own 250ms granularity, plus room for a seek that resolves to a
 * nearby sync frame rather than the exact millisecond asked for.
 */
private const val LATCH_TOLERANCE_MS = 750L

/** Ceiling on the hold, for a seek that never lands at all. */
private const val LATCH_TIMEOUT_MS = 1_500L

/** The seek control's accessible name. Not "wave" — TalkBack needs the function, not the look. */
private const val SCRUB_LABEL = "Playback position"

/** Wisp's scrub canvas is 34dp with the baseline 13dp up; this is that, doubled. */
private const val CANVAS_HEIGHT_DP = 68
private const val BASELINE_FROM_BOTTOM_DP = 26
private const val AMPLITUDE_DP = 22
private const val TAPER_END_DP = 16
private const val TRACK_STROKE_DP = 2
private const val TRACK_ALPHA = 0.22f
private const val THUMB_RADIUS_DP = 5f
private const val THUMB_RADIUS_DRAGGING_DP = 8f
private const val READOUT_INSET_DP = 4
private const val READOUT_ALPHA = 0.8f
private const val WAVE_STYLE = "wisptrail"
