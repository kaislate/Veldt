// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.WispPresence
import com.kaislate.veldtplayer.pill.util.IslandPosition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The thin edge between [SettingsScreen] and [SettingsRepository]. Holds no theme-resolution
 * logic of its own — that decision lives only in `ui/theme/Theme.kt`, per
 * `ThemeSourceGuardTest` — this just carries the stored values and the writes that change them.
 *
 * The built-in pill's section (P1.5c Task 2) additionally takes [WispPresence] and
 * [OverlayPermission] — real seams in production, fakes in the view model's test — because,
 * unlike the theme/metered-cap/lyrics settings above, "is Wisp installed" and "is the overlay
 * permission granted" are read from outside [SettingsRepository] entirely. `AppForeground` has
 * no seam taken here: nothing on this screen depends on Veldt's own foreground state, so it
 * belongs to whichever component actually drives the pill at runtime (a later task), not here.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val wispPresence: WispPresence,
    private val overlayPermission: OverlayPermission,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = settingsRepository.themeMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ThemeMode.SYSTEM,
    )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /** The mobile-data bitrate cap in kbps; 0 is original quality. */
    val meteredMaxBitRate: StateFlow<Int> = settingsRepository.meteredMaxBitRate.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0,
    )

    fun setMeteredMaxBitRate(kbps: Int) {
        viewModelScope.launch { settingsRepository.setMeteredMaxBitRate(kbps) }
    }

    /** The LRCLIB opt-in (spec §8): off by default. */
    val lyricsOnline: StateFlow<Boolean> = settingsRepository.lyricsOnline.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    fun setLyricsOnline(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLyricsOnline(enabled) }
    }

    // ---- Built-in pill (P1.5c Task 2). Settings screen "Floating pill" section, spec §4. ----

    /** The three-way mode. Defaults to [PillMode.BUILT_IN] (spec §4) before the first read. */
    val pillMode: StateFlow<PillMode> = settingsRepository.pillMode.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PillMode.BUILT_IN,
    )

    fun setPillMode(mode: PillMode) {
        viewModelScope.launch { settingsRepository.setPillMode(mode) }
    }

    /** The "use built-in anyway" override; only meaningful while [wispInstalled]. */
    val pillForceBuiltIn: StateFlow<Boolean> = settingsRepository.pillForceBuiltIn.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    fun setPillForceBuiltIn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPillForceBuiltIn(enabled) }
    }

    /**
     * Whether Veldt Wisp is installed right now — drives the settings screen's "Veldt Wisp is
     * installed, Veldt defers to it" note. [WispPresence.installed] is already a `StateFlow`
     * kept current by a package-change receiver (Task 2), so this only needs to follow it.
     */
    val wispInstalled: StateFlow<Boolean> = wispPresence.installed.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    /**
     * The overlay permission's status for the settings screen's status row.
     *
     * There is no permission-changed broadcast for `SYSTEM_ALERT_WINDOW` (see
     * [OverlayPermission]'s KDoc), so this is a plain [MutableStateFlow] the screen refreshes
     * itself via [refreshOverlayPermissionStatus] — read once at construction so the row shows
     * the right thing immediately, and again whenever the screen comes back to the foreground
     * (the settings screen's `ON_RESUME`, after a trip to `ACTION_MANAGE_OVERLAY_PERMISSION`).
     */
    private val _overlayPermissionGranted = MutableStateFlow(overlayPermission.isGranted())
    val overlayPermissionGranted: StateFlow<Boolean> = _overlayPermissionGranted.asStateFlow()

    fun refreshOverlayPermissionStatus() {
        _overlayPermissionGranted.value = overlayPermission.isGranted()
    }

    /** Where the pill docks on screen. */
    val pillAnchor: StateFlow<IslandPosition> = settingsRepository.pillAnchor.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        IslandPosition.TOP_CENTER,
    )

    fun setPillAnchor(position: IslandPosition) {
        viewModelScope.launch { settingsRepository.setPillAnchor(position) }
    }

    /** The collapsed pill's text width, in dp. */
    val pillWidthDp: StateFlow<Int> = settingsRepository.pillWidthDp.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        160,
    )

    fun setPillWidthDp(dp: Int) {
        viewModelScope.launch { settingsRepository.setPillWidthDp(dp) }
    }

    /** Which wave rendering the pill draws. */
    val pillWaveStyle: StateFlow<String> = settingsRepository.pillWaveStyle.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "wisptrail",
    )

    fun setPillWaveStyle(style: String) {
        viewModelScope.launch { settingsRepository.setPillWaveStyle(style) }
    }

    /** Where the wave takes its colour from. */
    val pillWaveColor: StateFlow<String> = settingsRepository.pillWaveColor.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "accent-light",
    )

    fun setPillWaveColor(mode: String) {
        viewModelScope.launch { settingsRepository.setPillWaveColor(mode) }
    }

    /** Whether album art crossfades between tracks on the pill/card. */
    val pillArtCrossfade: StateFlow<Boolean> = settingsRepository.pillArtCrossfade.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )

    fun setPillArtCrossfade(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setPillArtCrossfade(enabled) }
    }

    /** How long a paused pill lingers before hiding, in milliseconds. */
    val pillHideDelayMs: StateFlow<Long> = settingsRepository.pillHideDelayMs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        25_000L,
    )

    fun setPillHideDelayMs(ms: Long) {
        viewModelScope.launch { settingsRepository.setPillHideDelayMs(ms) }
    }

    /** Which transport buttons the pill shows, or `"off"` for none. */
    val pillTransportButtons: StateFlow<String> = settingsRepository.pillTransportButtons.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        "off",
    )

    fun setPillTransportButtons(value: String) {
        viewModelScope.launch { settingsRepository.setPillTransportButtons(value) }
    }
}
