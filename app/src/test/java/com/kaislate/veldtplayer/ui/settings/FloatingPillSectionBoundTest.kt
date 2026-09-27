// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.VolumeNames
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.PillStatus
import com.kaislate.veldtplayer.pill.PillVisibility
import com.kaislate.veldtplayer.pill.PillVisibilityInputs
import com.kaislate.veldtplayer.pill.WispPresence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The "Floating pill" section bound to a real [SettingsViewModel] and [SettingsRepository]
 * (Step 5 spec §9): the switch's mapping to `pill_mode`, the retired USE_WISP from the store to
 * the screen to the eligibility decision, and Wisp arriving or leaving while the section is open.
 *
 * `Dispatchers.Main` is unconfined here, as in [SettingsViewModelTest]: the view model's flows
 * then never wait on the main looper, which this (main-thread) test blocks whenever it touches
 * DataStore. Every blocking DataStore call has a timeout, so a regression fails rather than hangs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class FloatingPillSectionBoundTest {

    @get:Rule val compose = createComposeRule()

    private class FakeWispPresence(installed: Boolean) : WispPresence {
        val flow = MutableStateFlow(installed)
        override val installed: StateFlow<Boolean> = flow
    }

    private class FakeOverlayPermission(var granted: Boolean) : OverlayPermission {
        override fun isGranted() = granted
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repo: SettingsRepository
    private val wisp = FakeWispPresence(installed = false)

    private fun <T> store(block: suspend CoroutineScope.() -> T): T =
        runBlocking { withTimeout(5_000, block) }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = SettingsRepository(context)
        store { repo.clearForTest() }
    }

    @After fun tearDown() {
        store { repo.clearForTest() }
        Dispatchers.resetMain()
    }

    private fun showBound() {
        val vm = SettingsViewModel(
            repo, wisp, FakeOverlayPermission(true), PillStatus(), VolumeNames(context),
        )
        compose.setContent {
            Column { FloatingPillSection(vm = vm, onOpenAppearance = {}, startActivity = { true }) }
        }
    }

    private fun switchIsOn(): Boolean? = compose.pillSwitch.fetchSemanticsNode().config
        .getOrElseNullable(SemanticsProperties.ToggleableState) { null }
        ?.let { it == ToggleableState.On }

    /**
     * The retired USE_WISP, end to end: stored → the switch shows on → eligible without Wisp,
     * standing down for Wisp with no override. Starts from OFF so the switch's "on" has to come
     * from the stored value, not from the before-first-read default (which is also on).
     */
    @Test fun `a stored legacy USE_WISP shows the switch on and means the pill`() {
        store { repo.setPillMode(PillMode.OFF) }
        showBound()
        compose.waitUntil(5_000) { switchIsOn() == false }

        store { repo.writeRawPillModeForTest(PillMode.LEGACY_USE_WISP) }
        runCatching { compose.waitUntil(5_000) { switchIsOn() == true } }
        val shownOn = switchIsOn()

        val inputs = PillVisibilityInputs(mode = store { repo.pillMode.first() }, overlayGranted = true)
        assertEquals(
            listOf<Any?>(true, true, false),
            listOf<Any?>(
                shownOn,
                PillVisibility.decide(inputs),
                PillVisibility.decide(inputs.copy(wispInstalled = true)),
            ),
        )
    }

    @Test fun `the switch writes off then built-in to pill_mode`() {
        showBound()
        compose.pillSwitch.performClick()
        runCatching { compose.waitUntil(5_000) { store { repo.readRawForTest("pill_mode") } == "OFF" } }
        val afterOff = store { repo.readRawForTest("pill_mode") }
        compose.pillSwitch.performClick()
        runCatching {
            compose.waitUntil(5_000) { store { repo.readRawForTest("pill_mode") } == "BUILT_IN" }
        }
        val afterOn = store { repo.readRawForTest("pill_mode") }
        assertEquals(listOf("OFF", "BUILT_IN"), listOf(afterOff, afterOn))
    }

    /** Wisp installed or removed while Settings is open changes the section there and then. */
    @Test fun `Wisp arriving and leaving updates the section live`() {
        showBound()
        compose.waitForIdle()
        val before = compose.shownPillRows()
        wisp.flow.value = true
        compose.waitForIdle()
        val withWisp = compose.shownPillRows()
        wisp.flow.value = false
        compose.waitForIdle()
        val after = compose.shownPillRows()
        val noWisp = listOf(
            "A now-playing pill when you leave Veldt",
            "Pill appearance",
            "Get Veldt Wisp",
            "The standalone pill, for every music app",
        )
        val wispShowing = listOf(
            "Veldt Wisp is showing the pill",
            "Open Veldt Wisp",
            "Use Veldt's own pill instead",
        )
        assertEquals(listOf(noWisp, wispShowing, noWisp), listOf(before, withWisp, after))
    }
}
