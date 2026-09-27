// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.playback.sleep.SleepTimer
import com.kaislate.veldtplayer.playback.sleep.SleepTimerState
import com.kaislate.veldtplayer.ui.theme.DominantColors
import kotlin.math.roundToInt

private val SHEET_INSET = 20.dp
private val ROW_SHAPE = RoundedCornerShape(12.dp)
private const val HANDLE_ALPHA = 0.4f

/** Where a new custom duration starts: the middle preset, a common "one episode" length. */
private const val CUSTOM_DEFAULT_MINUTES = 30f

/**
 * How long until [state] stops the music, in ms, or null when there is no timer. A timed timer
 * counts down on the elapsed-realtime clock ([nowElapsedMs]); "end of this track" is whatever is
 * left of the track, so it pauses when playback does, as the track itself does.
 */
internal fun sleepRemainingMs(
    state: SleepTimerState,
    nowElapsedMs: Long,
    positionMs: Long,
    durationMs: Long,
): Long? = when (state) {
    SleepTimerState.Off -> null
    is SleepTimerState.Timed -> (state.endsAtElapsedMs - nowElapsedMs).coerceAtLeast(0)
    SleepTimerState.EndOfTrack -> if (durationMs > 0) (durationMs - positionMs).coerceAtLeast(0) else null
}

/** "4:05", or "1:02:03" past the hour. Rounded UP to the second, so it never reads 0:00 early. */
internal fun formatSleepRemaining(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The sleep timer's options (spec §4), in a sheet over now playing. Palette-themed for the reason
 * [QueueSheet] is: it is an extension of that surface, and its text must be solved against the
 * same ground. [SleepTimerOptions] is the content, separate so it is testable without a sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    state: SleepTimerState,
    remaining: String?,
    palette: DominantColors,
    onSetMinutes: (Int) -> Unit,
    onSetEndOfTrack: () -> Unit,
    onExtend: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.bg,
        contentColor = palette.onBg,
        // Same handle as the queue sheet: the default tint has no contrast against a palette ground.
        dragHandle = { BottomSheetDefaults.DragHandle(color = palette.onBg.copy(alpha = HANDLE_ALPHA)) },
    ) {
        SleepTimerOptions(
            state = state,
            remaining = remaining,
            palette = palette,
            // Choosing a length is the whole errand: the sheet gets out of the way. "+10 min"
            // stays open, so a second tap is a second ten minutes the user can watch arrive.
            onSetMinutes = { onSetMinutes(it); onDismiss() },
            onSetEndOfTrack = { onSetEndOfTrack(); onDismiss() },
            onExtend = onExtend,
            onCancel = { onCancel(); onDismiss() },
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
        )
    }
}

/**
 * While a timer runs: what it will do and when, "+10 min" and "Cancel timer". Always: the four
 * presets, "End of this track", and a custom length of 1–180 minutes. Picking any of them replaces
 * a running timer rather than stacking on it.
 */
@Composable
internal fun SleepTimerOptions(
    state: SleepTimerState,
    remaining: String?,
    palette: DominantColors,
    onSetMinutes: (Int) -> Unit,
    onSetEndOfTrack: () -> Unit,
    onExtend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var custom by rememberSaveable { mutableFloatStateOf(CUSTOM_DEFAULT_MINUTES) }
    val customMinutes = custom.roundToInt()
        .coerceIn(SleepTimer.MIN_MINUTES, SleepTimer.MAX_MINUTES)
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = palette.onBg)

    Column(modifier.padding(bottom = 12.dp)) {
        Text(
            "Sleep timer",
            style = MaterialTheme.typography.titleMedium,
            color = palette.onBg,
            modifier = Modifier.padding(horizontal = SHEET_INSET, vertical = 4.dp),
        )
        if (state != SleepTimerState.Off) {
            Text(
                when (state) {
                    is SleepTimerState.Timed -> "Pausing in ${remaining ?: "…"}"
                    else -> "Pausing at the end of this track" + (remaining?.let { " ($it)" } ?: "")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = palette.onBgSecondary,
                modifier = Modifier.padding(horizontal = SHEET_INSET, vertical = 4.dp),
            )
            Row(
                modifier = Modifier.padding(horizontal = SHEET_INSET - 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onExtend, colors = buttonColors) { Text("+10 min") }
                TextButton(onClick = onCancel, colors = buttonColors) { Text("Cancel timer") }
            }
        }
        SleepTimer.PRESET_MINUTES.forEach { minutes ->
            OptionRow("$minutes minutes", palette) { onSetMinutes(minutes) }
        }
        OptionRow("End of this track", palette, onSetEndOfTrack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SHEET_INSET, end = SHEET_INSET - 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Custom: $customMinutes min",
                style = MaterialTheme.typography.bodyLarge,
                color = palette.onBg,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSetMinutes(customMinutes) }, colors = buttonColors) {
                Text("Start")
            }
        }
        Slider(
            value = custom,
            onValueChange = { custom = it },
            valueRange = SleepTimer.MIN_MINUTES.toFloat()..SleepTimer.MAX_MINUTES.toFloat(),
            // One stop per whole minute: 1, 2, … 180 is 180 values, so 178 stops between the ends.
            steps = SleepTimer.MAX_MINUTES - SleepTimer.MIN_MINUTES - 1,
            colors = SliderDefaults.colors(
                thumbColor = palette.onBg,
                activeTrackColor = palette.onBg,
                inactiveTrackColor = palette.onBgSecondary,
                // Ticks the colour of the track they sit on: 178 of them would otherwise turn the
                // track into a dotted line.
                activeTickColor = palette.onBg,
                inactiveTickColor = palette.onBgSecondary,
            ),
            modifier = Modifier
                .padding(horizontal = SHEET_INSET)
                .semantics { contentDescription = "Custom sleep timer length" },
        )
    }
}

@Composable
private fun OptionRow(label: String, palette: DominantColors, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = palette.onBg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHEET_INSET - 8.dp)
            .clip(ROW_SHAPE)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
