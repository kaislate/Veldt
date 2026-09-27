// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.pill.util.IslandPosition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Everything the pill and the card read from settings, resolved into the exact parameters the
 * composables ported from Veldt Wisp take.
 *
 * Wisp had one setting per parameter; Veldt persists only the `pill_*` keys spec §4 lists
 * (see [SettingsRepository]). The Wisp parameters with no Veldt key are pinned here to Wisp's
 * own shipped defaults (`SettingsDefaults` in Wisp), so the port looks the way Wisp looks out
 * of the box rather than the way its composables' parameter defaults happen to read.
 *
 * A plain value, resolved by a pure function ([from]), so the settings→appearance mapping is
 * asserted without a device.
 */
data class PillAppearance(
    val position: IslandPosition,
    val pillTextWidthDp: Int,
    val panelWidthDp: Int,
    val topOffsetDp: Int,
    val waveStyle: String,
    val waveColorMode: String,
    val crossfadeMs: Int,
    val showPillControls: Boolean,
    val pillControlSet: String,
    val pillControlPosition: String,
    val thumbShape: String,
    val consume: Boolean,
    val vibrant: Boolean,
) {
    companion object {
        /** `pill_transport_buttons` value meaning "no buttons" (see [SettingsRepository.pillTransportButtons]). */
        const val TRANSPORT_OFF = "off"

        /** Wisp `PANEL_WIDTH_DP`. No Veldt key: `pill_width` is the collapsed pill's (Task 2). */
        const val PANEL_WIDTH_DP = 400

        /** Wisp `TOP_OFFSET_DP` — distance from the anchored edge. No Veldt key. */
        const val TOP_OFFSET_DP = 40

        /** Wisp `CROSSFADE_MS`, used while `pill_art_crossfade` is on. */
        const val CROSSFADE_MS = 1000

        /** Wisp `THUMB_SHAPE`. No Veldt key. */
        const val THUMB_SHAPE = "bar"

        /** Wisp `PILL_CONTROL_POSITION`. No Veldt key (dropped in Task 2). */
        const val CONTROL_POSITION = "right"

        /** Wisp `CONSUME_PROGRESS`. No Veldt key. */
        const val CONSUME = true

        /**
         * Wisp `VIBRANT_WAVE` (off). No Veldt key — and the vibrant gradients are raw swatch
         * colours with no contrast guarantee, which Veldt's solved-tone rule would not allow
         * anyway, so it stays off rather than being guessed at.
         */
        const val VIBRANT = false

        /** Resolves the persisted `pill_*` values. */
        fun from(
            anchor: IslandPosition,
            widthDp: Int,
            waveStyle: String,
            waveColor: String,
            artCrossfade: Boolean,
            transportButtons: String,
        ): PillAppearance = PillAppearance(
            position = anchor,
            pillTextWidthDp = widthDp,
            panelWidthDp = PANEL_WIDTH_DP,
            topOffsetDp = TOP_OFFSET_DP,
            // Passed through as-is: Veldt's drawWave recognises every key Wisp's did and sends
            // an unknown one to the hills renderer, exactly as Wisp's own dispatcher does.
            waveStyle = waveStyle,
            waveColorMode = waveColor,
            crossfadeMs = if (artCrossfade) CROSSFADE_MS else 0,
            showPillControls = transportButtons != TRANSPORT_OFF,
            // When "off", the set is never read (arrangementFor ignores it); anything else is
            // the control-set string PillLayout.buttonsFor already parses.
            pillControlSet = transportButtons,
            pillControlPosition = CONTROL_POSITION,
            thumbShape = THUMB_SHAPE,
            consume = CONSUME,
            vibrant = VIBRANT,
        )

        /** What the overlay draws before the first settings emission lands. Spec §4 defaults. */
        val DEFAULT: PillAppearance = from(
            anchor = IslandPosition.TOP_CENTER,
            widthDp = 160,
            waveStyle = "wisptrail",
            waveColor = "accent-light",
            artCrossfade = true,
            transportButtons = TRANSPORT_OFF,
        )
    }
}

/** The live [PillAppearance], following every `pill_*` setting it depends on. */
fun SettingsRepository.pillAppearance(): Flow<PillAppearance> = combine(
    pillAnchor, pillWidthDp, pillWaveStyle, pillWaveColor, pillArtCrossfade, pillTransportButtons,
) { values ->
    PillAppearance.from(
        anchor = values[0] as IslandPosition,
        widthDp = values[1] as Int,
        waveStyle = values[2] as String,
        waveColor = values[3] as String,
        artCrossfade = values[4] as Boolean,
        transportButtons = values[5] as String,
    )
}
