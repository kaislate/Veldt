// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.PillStandDown
import com.kaislate.veldtplayer.pill.PillStatus
import com.kaislate.veldtplayer.pill.WispPresence
import com.kaislate.veldtplayer.pill.util.IslandPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The built-in pill's "Floating pill" settings section (P1.5c Task 2), from the screen's side:
 * that the view model carries [SettingsRepository]'s pill settings through unchanged, that
 * [WispPresence.installed] surfaces as [SettingsViewModel.wispInstalled], and that the overlay
 * permission's status is read once at construction and again only when
 * [SettingsViewModel.refreshOverlayPermissionStatus] is called — never on its own, since there
 * is no permission-changed signal to drive it (see [OverlayPermission]'s KDoc).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private class FakeWispPresence(installed: Boolean) : WispPresence {
        private val _installed = MutableStateFlow(installed)
        override val installed: StateFlow<Boolean> = _installed
        fun setInstalled(value: Boolean) { _installed.value = value }
    }

    private class FakeOverlayPermission(@Volatile var granted: Boolean) : OverlayPermission {
        override fun isGranted(): Boolean = granted
    }

    private lateinit var repo: SettingsRepository
    private lateinit var wisp: FakeWispPresence
    private lateinit var overlay: FakeOverlayPermission
    private lateinit var pillStatus: PillStatus

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        runBlocking { repo.clearForTest() }
        wisp = FakeWispPresence(installed = false)
        overlay = FakeOverlayPermission(granted = false)
        pillStatus = PillStatus()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(repo, wisp, overlay, pillStatus)

    // `vm.setXxx` is fire-and-forget (`viewModelScope.launch { ... }`, same as every other
    // setter in SettingsViewModel), and DataStore's own `edit` genuinely suspends on its own
    // internal dispatcher rather than completing synchronously under UnconfinedTestDispatcher.
    // A bare `.first()` right after a setter call can therefore win the race and read the OLD
    // value; `.first { it == expected }` waits for the real eventual write instead of assuming
    // it already landed.

    @Test fun `the pill mode defaults to built-in and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(PillMode.BUILT_IN, vm.pillMode.first())
        vm.setPillMode(PillMode.OFF)
        assertEquals(PillMode.OFF, vm.pillMode.first { it == PillMode.OFF })
        assertEquals(PillMode.OFF, repo.pillMode.first())
    }

    @Test fun `force built-in defaults to off and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(false, vm.pillForceBuiltIn.first())
        vm.setPillForceBuiltIn(true)
        assertEquals(true, vm.pillForceBuiltIn.first { it })
        assertEquals(true, repo.pillForceBuiltIn.first())
    }

    @Test fun `wisp installed follows WispPresence, not a snapshot taken at construction`() = runTest {
        val vm = viewModel()
        assertEquals(false, vm.wispInstalled.first())
        wisp.setInstalled(true)
        assertEquals(
            "the view model must observe WispPresence.installed, not copy its value once",
            true,
            vm.wispInstalled.first(),
        )
    }

    @Test fun `the overlay permission status is read once at construction`() {
        overlay.granted = true
        val vm = viewModel()
        assertEquals(true, vm.overlayPermissionGranted.value)
    }

    @Test fun `the overlay permission status does not update on its own`() {
        val vm = viewModel()
        assertEquals(false, vm.overlayPermissionGranted.value)
        overlay.granted = true
        assertEquals(
            "there is no permission-changed signal to drive this -- it must not update itself",
            false,
            vm.overlayPermissionGranted.value,
        )
    }

    @Test fun `refreshing the overlay permission status re-reads it`() {
        val vm = viewModel()
        assertEquals(false, vm.overlayPermissionGranted.value)
        overlay.granted = true
        vm.refreshOverlayPermissionStatus()
        assertEquals(true, vm.overlayPermissionGranted.value)
    }

    @Test fun `the pill anchor defaults to top-center and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(IslandPosition.TOP_CENTER, vm.pillAnchor.first())
        vm.setPillAnchor(IslandPosition.BOTTOM_LEFT)
        assertEquals(IslandPosition.BOTTOM_LEFT, vm.pillAnchor.first { it == IslandPosition.BOTTOM_LEFT })
        assertEquals(IslandPosition.BOTTOM_LEFT, repo.pillAnchor.first())
    }

    @Test fun `the pill width defaults to 160dp and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(160, vm.pillWidthDp.first())
        vm.setPillWidthDp(200)
        assertEquals(200, vm.pillWidthDp.first { it == 200 })
        assertEquals(200, repo.pillWidthDp.first())
    }

    @Test fun `the pill wave style defaults to wisptrail and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals("wisptrail", vm.pillWaveStyle.first())
        vm.setPillWaveStyle("hills")
        assertEquals("hills", vm.pillWaveStyle.first { it == "hills" })
        assertEquals("hills", repo.pillWaveStyle.first())
    }

    @Test fun `the pill wave color defaults to accent-light and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals("accent-light", vm.pillWaveColor.first())
        vm.setPillWaveColor("white")
        assertEquals("white", vm.pillWaveColor.first { it == "white" })
        assertEquals("white", repo.pillWaveColor.first())
    }

    @Test fun `pill art crossfade defaults to on and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(true, vm.pillArtCrossfade.first())
        vm.setPillArtCrossfade(false)
        assertEquals(false, vm.pillArtCrossfade.first { !it })
        assertEquals(false, repo.pillArtCrossfade.first())
    }

    @Test fun `the pill hide delay defaults to 25 seconds and round-trips`() = runTest {
        val vm = viewModel()
        assertEquals(25_000L, vm.pillHideDelayMs.first())
        vm.setPillHideDelayMs(5_000L)
        assertEquals(5_000L, vm.pillHideDelayMs.first { it == 5_000L })
        assertEquals(5_000L, repo.pillHideDelayMs.first())
    }

    @Test fun `pill transport buttons default to off and round-trip`() = runTest {
        val vm = viewModel()
        assertEquals("off", vm.pillTransportButtons.first())
        vm.setPillTransportButtons("play-next")
        assertEquals("play-next", vm.pillTransportButtons.first { it == "play-next" })
        assertEquals("play-next", repo.pillTransportButtons.first())
    }

    @Test fun `the pill stand-down status follows what the controller reports`() {
        val vm = viewModel()
        assertEquals(PillStandDown.NONE, vm.pillStandDown.value)
        pillStatus.report(PillStandDown.ATTACH_REFUSED)
        assertEquals(PillStandDown.ATTACH_REFUSED, vm.pillStandDown.value)
        pillStatus.report(PillStandDown.NONE)
        assertEquals(PillStandDown.NONE, vm.pillStandDown.value)
    }
}
