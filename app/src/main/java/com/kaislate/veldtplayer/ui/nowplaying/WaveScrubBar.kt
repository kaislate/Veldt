package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.ui.components.drawWave
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.VeldtText
import kotlin.math.PI

/**
 * The signature surface: Veldt Wisp's wave, at full-screen scale, as the seek control.
 *
 * P1.3 renders exactly ONE style — "wisptrail" with consume on (global constraint 7).
 * The other renderers are present but dormant until P1.5 adds the picker.
 *
 * [positionMs] is the transport's own position and drives the wave whenever the user is
 * not touching the bar; during a drag the bar shows the finger instead, so the playhead
 * never fights a position tick arriving mid-gesture.
 */
@Composable
fun WaveScrubBar(
    positionMs: Long,
    durationMs: Long,
    palette: DominantColors,
    reducedMotion: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val phase = rememberWavePhase(reducedMotion)

    // -1f means "not dragging". A nullable Float would box on every drag frame.
    var dragFraction by remember { mutableFloatStateOf(NOT_DRAGGING) }
    val isDragging = dragFraction >= 0f

    // A track with no duration cannot be seeked, and must not look like it can: the
    // gestures below are attached only when there is something to seek to, so the
    // playhead can never be dragged somewhere the transport will refuse to go.
    val seekable = durationMs > 0L

    val liveFraction =
        if (seekable) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val fraction = if (isDragging) dragFraction else liveFraction

    // Grows under the finger. Selected from the motion vocabulary rather than hard-cut
    // between two radii, which on the one element the user is actually touching reads
    // as a glitch rather than as feedback.
    val thumbRadiusDp by animateFloatAsState(
        targetValue = if (isDragging) THUMB_RADIUS_DRAGGING_DP else THUMB_RADIUS_DP,
        animationSpec = Motion.snappy,
        label = "scrubThumbRadius",
    )

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CANVAS_HEIGHT_DP.dp)
                .then(
                    if (!seekable) Modifier else Modifier
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                onSeek(offset.x.fractionOf(size.width).toPositionMs(durationMs))
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
                    phase = phase,
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
                radius = thumbRadiusDp.dp.toPx(),
                center = Offset(playheadX, baseY),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = READOUT_INSET_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                formatTime(if (isDragging) dragFraction.toPositionMs(durationMs) else positionMs),
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
 * Every renderer's internal rates are chosen against that pairing, so neither the sweep
 * here nor the duration there can be changed alone without every style visibly
 * restarting once a loop. Returns a frozen phase under reduced motion, which leaves the
 * wave drawn but still.
 */
@Composable
private fun rememberWavePhase(reducedMotion: Boolean): Float {
    if (reducedMotion) return 0f
    val transition = rememberInfiniteTransition(label = "wavePhase")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = PHASE_TURNS * PI.toFloat(),
        animationSpec = Motion.wavePhase,
        label = "wavePhaseValue",
    )
    return phase
}

/** See [rememberWavePhase]: paired with `Motion.wavePhase`'s duration. */
private const val PHASE_TURNS = 20f

private const val NOT_DRAGGING = -1f

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
