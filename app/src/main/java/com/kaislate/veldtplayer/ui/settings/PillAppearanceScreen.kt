// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.pill.util.IslandPosition

/**
 * The built-in pill's appearance (Step 5 spec §9): every option that used to sit inline in the
 * Settings "Floating pill" section, unchanged, on a page of its own. Moved because they only
 * apply to Veldt's own pill, and inline they buried the one decision the section is for —
 * whether there is a pill, and whose.
 *
 * Takes its own [SettingsViewModel] (a new instance for this back stack entry): the appearance
 * values are all DataStore reads and writes, so two instances see the same settings.
 */
@Composable
fun PillAppearanceScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val pillAnchor by vm.pillAnchor.collectAsStateWithLifecycle()
    val pillWidthDp by vm.pillWidthDp.collectAsStateWithLifecycle()
    val pillWaveStyle by vm.pillWaveStyle.collectAsStateWithLifecycle()
    val pillWaveColor by vm.pillWaveColor.collectAsStateWithLifecycle()
    val pillArtCrossfade by vm.pillArtCrossfade.collectAsStateWithLifecycle()
    val pillHideDelayMs by vm.pillHideDelayMs.collectAsStateWithLifecycle()
    val pillTransportButtons by vm.pillTransportButtons.collectAsStateWithLifecycle()
    val direction = LocalLayoutDirection.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            )
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        SettingsHeader(title = "Pill appearance", onBack = onBack)

        RadioGroup(
            caption = "Anchor",
            options = listOf(
                IslandPosition.TOP_LEFT to "Top left",
                IslandPosition.TOP_CENTER to "Top center",
                IslandPosition.TOP_RIGHT to "Top right",
                IslandPosition.BOTTOM_LEFT to "Bottom left",
                IslandPosition.BOTTOM_CENTER to "Bottom center",
                IslandPosition.BOTTOM_RIGHT to "Bottom right",
            ),
            selected = pillAnchor,
            onSelect = vm::setPillAnchor,
        )

        RadioGroup(
            caption = "Width",
            options = listOf(120 to "Narrow", 160 to "Default", 200 to "Wide", 240 to "Extra wide"),
            selected = pillWidthDp,
            onSelect = vm::setPillWidthDp,
        )

        RadioGroup(
            caption = "Wave style",
            options = listOf(
                "wisptrail" to "Wisptrail",
                "mercury" to "Mercury",
                "silk" to "Silk",
                "sparks" to "Sparks",
                "aurora" to "Aurora",
            ),
            selected = pillWaveStyle,
            onSelect = vm::setPillWaveStyle,
        )

        RadioGroup(
            caption = "Wave colour",
            options = listOf(
                "accent-light" to "Accent, lightened",
                "auto" to "Automatic",
                // Persisted as "white" (Wisp's value); it draws the solved TEXT tone,
                // which is dark on a light ground, so the label says what it does.
                "white" to "Match text",
            ),
            selected = pillWaveColor,
            onSelect = vm::setPillWaveColor,
        )

        SwitchRow(
            label = "Album art crossfade",
            explanation = "Fades between album art instead of cutting when the track changes.",
            checked = pillArtCrossfade,
            onCheckedChange = vm::setPillArtCrossfade,
        )

        RadioGroup(
            caption = "Hide delay after pause",
            options = listOf(
                10_000L to "10 seconds",
                15_000L to "15 seconds",
                25_000L to "25 seconds",
                45_000L to "45 seconds",
            ),
            selected = pillHideDelayMs,
            onSelect = vm::setPillHideDelayMs,
        )

        RadioGroup(
            caption = "Transport buttons",
            options = listOf(
                "off" to "None",
                "play" to "Play/pause",
                "play-next" to "Play/pause and next",
                "prev-play-next" to "Previous, play/pause and next",
            ),
            selected = pillTransportButtons,
            onSelect = vm::setPillTransportButtons,
        )
    }
}
