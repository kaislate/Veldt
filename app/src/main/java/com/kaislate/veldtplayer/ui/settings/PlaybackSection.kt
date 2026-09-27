// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import com.kaislate.veldtplayer.data.replaygain.ReplayGainPreamp
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel
import kotlin.math.roundToInt

/** "+3 dB", "0 dB", "−3 dB" (a true minus sign, as the pre-amp is read, not typed). */
internal fun formatPreamp(db: Int): String = when {
    db > 0 -> "+$db dB"
    db < 0 -> "−${-db} dB"
    else -> "0 dB"
}

/** [PlaybackSection], fed from [vm]. */
@Composable
internal fun PlaybackSection(vm: SettingsViewModel) {
    val mode by vm.replayGainMode.collectAsStateWithLifecycle()
    val preamp by vm.replayGainPreampDb.collectAsStateWithLifecycle()
    PlaybackSection(
        mode = mode,
        preampDb = preamp,
        onModeChange = vm::setReplayGainMode,
        onPreampChange = vm::setReplayGainPreampDb,
    )
}

/**
 * The Settings "Playback" section (0.9.2 spec §5): the ReplayGain mode and its pre-amp.
 *
 * The pre-amp slider stays visible with ReplayGain off, but disabled: it is still the setting that
 * will apply when it is turned back on, and hiding it would make the section change shape under a
 * finger. Its label keeps the solved text tones either way — only the control itself is disabled.
 */
@Composable
internal fun PlaybackSection(
    mode: ReplayGainMode,
    preampDb: Int,
    onModeChange: (ReplayGainMode) -> Unit,
    onPreampChange: (Int) -> Unit,
) {
    SectionLabel("Playback")
    RadioGroup(
        caption = "ReplayGain: even out loudness between songs",
        options = listOf(
            ReplayGainMode.AUTO to "Auto (album gain when playing an album in order)",
            ReplayGainMode.TRACK to "Track gain",
            ReplayGainMode.ALBUM to "Album gain",
            ReplayGainMode.OFF to "Off",
        ),
        selected = mode,
        onSelect = onModeChange,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Pre-amp",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatPreamp(preampDb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = preampDb.toFloat(),
        // A drag reports every frame; only a new whole dB is a new setting to store.
        onValueChange = { v -> v.roundToInt().let { if (it != preampDb) onPreampChange(it) } },
        valueRange = ReplayGainPreamp.MIN_DB.toFloat()..ReplayGainPreamp.MAX_DB.toFloat(),
        // Whole dB: −6 … +6 is 13 values, 11 stops between the ends.
        steps = ReplayGainPreamp.MAX_DB - ReplayGainPreamp.MIN_DB - 1,
        enabled = mode != ReplayGainMode.OFF,
        modifier = Modifier
            .padding(horizontal = SIDE_MARGIN)
            .semantics {
                contentDescription = "ReplayGain pre-amp"
                stateDescription = formatPreamp(preampDb)
            },
    )
    Text(
        "Songs without ReplayGain tags play unchanged. Loud tracks are never boosted past clipping.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
    )
}
