// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.media.session.PlaybackState
import com.kaislate.veldtplayer.pill.domain.overlay.HideIslandUseCase
import com.kaislate.veldtplayer.pill.domain.overlay.OverlayRepository
import com.kaislate.veldtplayer.pill.domain.overlay.ShowIslandUseCase
import com.kaislate.veldtplayer.pill.overlay.IslandState
import com.kaislate.veldtplayer.pill.overlay.IslandStateMachine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PillController]'s decisions over time, with fakes for every seam and the REAL ported
 * [IslandStateMachine] underneath (so the hide delay and the media rules are the production
 * ones, on virtual time).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PillControllerTest {

    /** The overlay window, as the state machine (repository) and the controller (PillOverlay) see it. */
    private class FakeWindow : OverlayRepository, PillOverlay {
        var showing = false
        var refuse = false
        var attaches = 0
        var hides = 0
        private val _visible = MutableStateFlow(false)
        override val isPillVisible: StateFlow<Boolean> = _visible
        private val _failure = MutableStateFlow<ShowFailure?>(null)
        override val showFailure: StateFlow<ShowFailure?> = _failure
        override var onStashRequested: (() -> Unit)? = null

        override fun showPill() {
            attaches++
            if (refuse) {
                _failure.value = ShowFailure("refused")
            } else {
                showing = true
                _failure.value = null
            }
            _visible.value = true
        }

        override fun hidePill() = hide()

        override fun hide() {
            hides++
            showing = false
            _visible.value = false
        }
    }

    private class FakePermission(var granted: Boolean) : OverlayPermission {
        override fun isGranted() = granted
    }

    private class FakeNotice : StashNotice {
        var shown = false
        var onTap: (() -> Unit)? = null
        override fun show(onTap: () -> Unit) { shown = true; this.onTap = onTap }
        override fun cancel() { shown = false; onTap = null }
    }

    private class Rig(test: TestScope, modeFlow: Flow<PillMode>? = null) {
        val mode = MutableStateFlow(PillMode.BUILT_IN)
        val force = MutableStateFlow(false)
        val hideDelay = MutableStateFlow(10_000L)
        val wisp = MutableStateFlow(false)
        val foreground = MutableStateFlow(true)
        val playback = MutableStateFlow<Int?>(null)
        val permission = FakePermission(true)
        val window = FakeWindow()
        val status = PillStatus()
        val notice = FakeNotice()
        val machine = IslandStateMachine(
            ShowIslandUseCase(window), HideIslandUseCase(window), test.backgroundScope,
        ) { test.testScheduler.currentTime }
        val controller = PillController(
            parentScope = test.backgroundScope,
            mode = modeFlow ?: mode,
            forceBuiltIn = force,
            hideDelayMs = hideDelay,
            wispInstalled = wisp,
            appInForeground = foreground,
            playbackState = playback,
            permission = permission,
            stateMachine = machine,
            overlay = window,
            status = status,
            stashNotice = notice,
        )
    }

    /** Veldt open and playing, then the user leaves it: the ordinary way the pill appears. */
    private fun TestScope.playingInBackground(rig: Rig = Rig(this)): Rig {
        rig.controller.start()
        rig.playback.value = PlaybackState.STATE_PLAYING
        runCurrent()
        rig.foreground.value = false
        runCurrent()
        return rig
    }

    @Test fun `leaving Veldt while playing shows the pill, and not before`() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        rig.playback.value = PlaybackState.STATE_PLAYING
        runCurrent()
        assertFalse("never over Veldt itself", rig.window.showing)
        rig.foreground.value = false
        runCurrent()
        assertTrue(rig.window.showing)
    }

    @Test fun `returning to Veldt hides the pill`() = runTest {
        val rig = playingInBackground()
        rig.foreground.value = true
        runCurrent()
        assertFalse(rig.window.showing)
    }

    @Test fun `a pause hides the pill after the hide delay, not before`() = runTest {
        val rig = playingInBackground()
        rig.playback.value = PlaybackState.STATE_PAUSED
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue("still inside the hide delay", rig.window.showing)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(rig.window.showing)
    }

    @Test fun `the hide delay follows the setting`() = runTest {
        val rig = Rig(this)
        rig.hideDelay.value = 3_000L
        playingInBackground(rig)
        rig.playback.value = PlaybackState.STATE_PAUSED
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        assertFalse(rig.window.showing)
    }

    @Test fun `Wisp being installed hides the pill at once`() = runTest {
        val rig = playingInBackground()
        rig.wisp.value = true
        runCurrent()
        assertFalse(rig.window.showing)
        rig.wisp.value = false
        runCurrent()
        assertTrue("uninstalling Wisp brings it back", rig.window.showing)
    }

    @Test fun `the override keeps the pill with Wisp installed`() = runTest {
        val rig = Rig(this)
        rig.force.value = true
        playingInBackground(rig)
        rig.wisp.value = true
        runCurrent()
        assertTrue(rig.window.showing)
    }

    /**
     * Step 5 §9: the retired "Use Veldt Wisp" choice, still on disk, reads as the switch on. So
     * without Wisp Veldt now draws the pill, and with Wisp it stands down exactly as before.
     */
    @Test fun `a stored legacy USE_WISP shows the pill without Wisp and stands down for it`() = runTest {
        val rig = Rig(this)
        rig.mode.value = PillMode.fromStored(PillMode.LEGACY_USE_WISP)
        playingInBackground(rig)
        val withoutWisp = rig.window.showing
        rig.wisp.value = true
        runCurrent()
        assertEquals(listOf(true, false), listOf(withoutWisp, rig.window.showing))
    }

    @Test fun `mode OFF never shows, through any playback or foreground change`() = runTest {
        val rig = Rig(this)
        rig.mode.value = PillMode.OFF
        playingInBackground(rig)
        listOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING).forEach {
            rig.playback.value = it
            runCurrent()
        }
        rig.foreground.value = true; runCurrent()
        rig.foreground.value = false; runCurrent()
        assertEquals(0, rig.window.attaches)
    }

    @Test fun `switching the mode off while showing hides it`() = runTest {
        val rig = playingInBackground()
        rig.mode.value = PillMode.OFF
        runCurrent()
        assertFalse(rig.window.showing)
    }

    @Test fun `nothing can show before the settings' first read lands`() = runTest {
        val pending = MutableSharedFlow<PillMode>(replay = 1)
        val rig = Rig(this, modeFlow = pending)
        playingInBackground(rig)
        assertEquals(0, rig.window.attaches)
        pending.emit(PillMode.BUILT_IN)
        runCurrent()
        assertTrue(rig.window.showing)
    }

    @Test fun `permission lost while showing stands down and says so`() = runTest {
        val rig = playingInBackground()
        rig.permission.granted = false
        advanceTimeBy(PillController.PERMISSION_POLL_MS + 1)
        runCurrent()
        assertFalse(rig.window.showing)
        assertEquals(PillStandDown.PERMISSION_LOST, rig.status.standDown.value)

        // Re-granted: the next event brings it back and clears the status.
        rig.permission.granted = true
        rig.playback.value = PlaybackState.STATE_BUFFERING
        runCurrent()
        assertTrue(rig.window.showing)
        assertEquals(PillStandDown.NONE, rig.status.standDown.value)
    }

    @Test fun `the permission poll ticks only while the pill is on screen`() = runTest {
        val rig = Rig(this)
        rig.controller.start()
        rig.playback.value = PlaybackState.STATE_PLAYING
        runCurrent()
        // Hidden (Veldt in the foreground) for a minute: not one wake-up.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, rig.controller.pollTicks)

        rig.foreground.value = false; runCurrent()
        advanceTimeBy(3 * PillController.PERMISSION_POLL_MS + 1); runCurrent()
        assertEquals(3, rig.controller.pollTicks)

        // Hidden again: the loop is cancelled, the count stops.
        rig.foreground.value = true; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, rig.controller.pollTicks)
    }

    @Test fun `a permission never granted is not reported as lost`() = runTest {
        val rig = Rig(this)
        rig.permission.granted = false
        playingInBackground(rig)
        assertFalse(rig.window.showing)
        assertEquals(PillStandDown.NONE, rig.status.standDown.value)
    }

    @Test fun `a refused attach stands down without retrying, and clears on the next good show`() = runTest {
        val rig = Rig(this)
        rig.window.refuse = true
        playingInBackground(rig)
        assertEquals(1, rig.window.attaches)
        assertEquals(PillStandDown.ATTACH_REFUSED, rig.status.standDown.value)
        assertEquals("stood down, not believed-shown", IslandState.Hidden, rig.machine.state.value)

        // Media events alone must not retry — that is the crash/refusal loop. Including a pause
        // that runs out its hide delay and a fresh play after it.
        rig.playback.value = PlaybackState.STATE_PAUSED; runCurrent()
        advanceTimeBy(20_000); runCurrent()
        listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED, PlaybackState.STATE_PLAYING)
            .forEach { rig.playback.value = it; runCurrent() }
        assertEquals(1, rig.window.attaches)

        // A fresh signal (the user visits Veldt and leaves again) retries, and success clears it.
        rig.window.refuse = false
        rig.foreground.value = true; runCurrent()
        rig.foreground.value = false; runCurrent()
        assertTrue(rig.window.showing)
        assertEquals(PillStandDown.NONE, rig.status.standDown.value)
    }

    @Test fun `a second identical refusal is still seen`() = runTest {
        val rig = Rig(this)
        rig.window.refuse = true
        playingInBackground(rig)
        rig.foreground.value = true; runCurrent()
        rig.foreground.value = false; runCurrent()
        assertEquals(2, rig.window.attaches)
        // Not believed-shown: a further media event must not attach a third time.
        rig.playback.value = PlaybackState.STATE_PAUSED; runCurrent()
        rig.playback.value = PlaybackState.STATE_PLAYING; runCurrent()
        assertEquals(2, rig.window.attaches)
        assertEquals(PillStandDown.ATTACH_REFUSED, rig.status.standDown.value)
    }

    @Test fun `the stash flick hides the pill and posts the notice, whose tap brings it back`() = runTest {
        val rig = playingInBackground()
        rig.window.onStashRequested!!.invoke()
        runCurrent()
        assertFalse(rig.window.showing)
        assertTrue(rig.notice.shown)
        assertTrue(rig.controller.isStashed)

        // Stashed survives media events (as in Wisp).
        rig.playback.value = PlaybackState.STATE_PAUSED; runCurrent()
        rig.playback.value = PlaybackState.STATE_PLAYING; runCurrent()
        assertFalse(rig.window.showing)

        rig.notice.onTap!!.invoke()
        runCurrent()
        assertTrue(rig.window.showing)
        assertFalse(rig.notice.shown)
    }

    @Test fun `opening Veldt un-stashes, so leaving it again shows the pill`() = runTest {
        val rig = playingInBackground()
        rig.window.onStashRequested!!.invoke()
        runCurrent()
        rig.foreground.value = true; runCurrent()
        assertFalse(rig.notice.shown)
        rig.foreground.value = false; runCurrent()
        assertTrue(rig.window.showing)
    }

    @Test fun `a stash is dropped, notice and all, once the pill could not come back`() = runTest {
        val rig = playingInBackground()
        rig.window.onStashRequested!!.invoke()
        runCurrent()
        rig.mode.value = PillMode.OFF
        runCurrent()
        assertFalse(rig.notice.shown)
        assertFalse(rig.controller.isStashed)
    }

    @Test fun `release removes the overlay and nothing shows afterwards`() = runTest {
        val rig = playingInBackground()
        rig.window.onStashRequested!!.invoke()
        rig.notice.onTap!!.invoke()
        runCurrent()
        rig.playback.value = PlaybackState.STATE_PAUSED   // arms the auto-hide
        runCurrent()
        assertTrue(rig.window.showing)

        rig.controller.release()
        assertFalse("overlay removed on release", rig.window.showing)
        assertFalse(rig.notice.shown)
        assertEquals(null, rig.window.onStashRequested)

        val attaches = rig.window.attaches
        val hides = rig.window.hides
        rig.playback.value = PlaybackState.STATE_PLAYING
        rig.foreground.value = true
        runCurrent()
        rig.foreground.value = false
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no attach after release", attaches, rig.window.attaches)
        assertEquals("no stray auto-hide after release", hides, rig.window.hides)
    }

    @Test fun `release is idempotent`() = runTest {
        val rig = playingInBackground()
        rig.controller.release()
        rig.controller.release()
        assertEquals(1, rig.window.hides)
    }

    @Test fun `stand-down status table`() {
        val cases = listOf(
            listOf(PillMode.BUILT_IN, true, true, false) to PillStandDown.NONE,
            listOf(PillMode.BUILT_IN, false, true, false) to PillStandDown.PERMISSION_LOST,
            listOf(PillMode.BUILT_IN, false, false, false) to PillStandDown.NONE,
            listOf(PillMode.BUILT_IN, true, true, true) to PillStandDown.ATTACH_REFUSED,
            listOf(PillMode.BUILT_IN, false, true, true) to PillStandDown.PERMISSION_LOST,
            listOf(PillMode.OFF, false, true, true) to PillStandDown.NONE,
            listOf(PillMode.OFF, true, true, true) to PillStandDown.NONE,
        )
        val actual = cases.map { (c, _) ->
            PillStatusRules.standDown(c[0] as PillMode, c[1] as Boolean, c[2] as Boolean, c[3] as Boolean)
        }
        assertEquals(cases.map { it.second }, actual)
        assertEquals(
            listOf(null, true, true),
            listOf(PillStandDown.NONE.message, PillStandDown.PERMISSION_LOST.message != null,
                PillStandDown.ATTACH_REFUSED.message?.contains("Display over other apps")),
        )
    }
}
