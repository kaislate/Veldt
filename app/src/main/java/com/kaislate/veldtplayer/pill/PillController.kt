// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import android.util.Log
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.pill.overlay.IslandState
import com.kaislate.veldtplayer.pill.overlay.IslandStateMachine
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the built-in pill for the life of `PlaybackService` (spec §3). Created with the
 * service, [start]ed in its `onCreate`, [release]d FIRST in its `onDestroy`.
 *
 * Two layers, per the controller ruling:
 *
 * - **Eligibility** — [PillVisibility.decide] over mode, the Wisp override, Wisp's presence,
 *   the overlay permission and whether Veldt itself is on screen. It is fed to the state machine
 *   as its `enabled` gate, so eligibility turning false hides the pill immediately (a closed gate
 *   always wins in `IslandRules`).
 * - **Runtime** — the ported [IslandStateMachine] decides show/hide from the media state
 *   (playing / paused / gone, the grace window) and runs the paused hide-delay timer.
 *
 * Plus two things the eligibility inputs cannot see:
 *
 * - **An attach Android refused** ([PillOverlay.showFailure]) stands the pill down — treated as
 *   ineligible, not retried in a loop — and is reported on [PillStatus] until the next attach
 *   succeeds. A retry is allowed only on a fresh signal from the user or the system (Veldt
 *   foregrounded, a settings change, the permission flipping back on).
 * - **The overlay permission has no change broadcast**, so it is re-read on every event and,
 *   while the pill is on screen, polled every [permissionPollMs].
 *
 * **Stash** (Wisp's swipe-away) is ported: the flick sets the state machine's `stashed` gate and
 * posts a "tap to bring it back" [StashNotice], whose tap clears it. One addition: Veldt coming
 * to the foreground also un-stashes. Wisp had no equivalent moment (it never owned the player);
 * in Veldt, opening the app is the direct "show me the player" gesture, and it is the only way
 * back when notifications are blocked.
 *
 * Main thread only: the state machine's effects end in `WindowManager` calls.
 */
@ServiceScoped
class PillController internal constructor(
    parentScope: CoroutineScope,
    private val mode: Flow<PillMode>,
    private val forceBuiltIn: Flow<Boolean>,
    private val hideDelayMs: Flow<Long>,
    private val wispInstalled: StateFlow<Boolean>,
    private val appInForeground: StateFlow<Boolean>,
    private val playbackState: StateFlow<Int?>,
    private val permission: OverlayPermission,
    private val stateMachine: IslandStateMachine,
    private val overlay: PillOverlay,
    private val status: PillStatus,
    private val stashNotice: StashNotice,
    private val permissionPollMs: Long = PERMISSION_POLL_MS,
) {

    @Inject
    constructor(
        settings: SettingsRepository,
        wisp: WispPresence,
        foreground: AppForeground,
        permission: OverlayPermission,
        stateMachine: IslandStateMachine,
        overlay: PillOverlay,
        status: PillStatus,
        stashNotice: StashNotice,
    ) : this(
        parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        mode = settings.pillMode,
        forceBuiltIn = settings.pillForceBuiltIn,
        hideDelayMs = settings.pillHideDelayMs,
        wispInstalled = wisp.installed,
        appInForeground = foreground.inForeground,
        playbackState = MediaSessionBus.playbackState,
        permission = permission,
        stateMachine = stateMachine,
        overlay = overlay,
        status = status,
        stashNotice = stashNotice,
    )

    /** A child of the given scope, so [release] cancels exactly what this controller started. */
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    private var started = false
    private var released = false

    /** Null until the settings' first emission: until then the pill is not eligible. */
    private var settings: Pair<PillMode, Boolean>? = null
    private var granted = false
    private var everGranted = false
    private var attachRefused = false
    private var refusalReported = false
    private var stashed = false

    /** Whether the user has the pill stashed (swiped away). For tests and logs. */
    val isStashed: Boolean get() = stashed

    fun start() {
        if (started || released) return
        started = true
        granted = permission.isGranted()
        everGranted = granted

        // Closed BEFORE anything is collected: the machine's default environment is "all gates
        // open", and the bus's current state would otherwise show the pill for the instant
        // before the settings' first read lands — over Veldt itself, or with the mode Off.
        stateMachine.updateEnvironment(enabled = false, appInForeground = appInForeground.value)
        overlay.onStashRequested = ::stash

        scope.launch { hideDelayMs.collect { stateMachine.setPausedTimeout(it) } }
        scope.launch {
            combine(mode, forceBuiltIn) { m, f -> m to f }.collect {
                settings = it
                attachRefused = false
                reevaluate()
            }
        }
        scope.launch { wispInstalled.collect { reevaluate() } }
        scope.launch {
            appInForeground.collect { fg ->
                if (fg) {
                    attachRefused = false
                    unstash()
                }
                reevaluate()
            }
        }
        scope.launch {
            overlay.showFailure.collect { failure ->
                if (failure != null) {
                    Log.w(TAG, "pill attach refused: ${failure.reason}; standing down")
                    attachRefused = true
                    refusalReported = true
                } else {
                    refusalReported = false
                }
                reevaluate()
            }
        }
        scope.launch {
            playbackState.collect { state ->
                reevaluate()
                stateMachine.onPlaybackChanged(state)
            }
        }
        scope.launch {
            while (true) {
                delay(permissionPollMs)
                if (stateMachine.state.value == IslandState.Pill) reevaluate()
            }
        }
    }

    /**
     * Stops everything and REMOVES THE OVERLAY (plan Review Focus 5: a leaked overlay window
     * outlives the service's UI and cannot be dismissed). Idempotent; nothing runs after it.
     */
    fun release() {
        if (released) return
        released = true
        job.cancel()
        overlay.onStashRequested = null
        // Shut the machine down before hiding, so an armed auto-hide cannot fire afterwards.
        stateMachine.shutdown()
        overlay.hide()
        stashNotice.cancel()
        stashed = false
    }

    /** The user flicked the pill away. */
    private fun stash() {
        if (stashed || released) return
        stashed = true
        stateMachine.updateEnvironment(stashed = true)
        stashNotice.show(onTap = ::unstash)
    }

    /** Brings a stashed pill back (the notice's tap, or Veldt coming to the foreground). */
    fun unstash() {
        if (!stashed || released) return
        stashed = false
        stashNotice.cancel()
        stateMachine.updateEnvironment(stashed = false)
    }

    private fun reevaluate() {
        if (released) return
        val nowGranted = permission.isGranted()
        if (nowGranted != granted) {
            granted = nowGranted
            // A re-grant is a fresh signal: a refusal recorded before it may not hold now.
            if (nowGranted) attachRefused = false
        }
        if (nowGranted) everGranted = true

        val s = settings
        val mode = s?.first ?: PillMode.OFF
        val inputs = PillVisibilityInputs(
            mode = mode,
            forceBuiltIn = s?.second ?: false,
            wispInstalled = wispInstalled.value,
            overlayGranted = granted,
            appInForeground = appInForeground.value,
        )
        val eligible = s != null && PillVisibility.decide(inputs) && !attachRefused

        // A stash only means something while the pill could come back. If it can't (mode off,
        // deferring to Wisp, permission gone), the "tap to bring it back" notice would lie.
        val couldShow = s != null && PillVisibility.decide(inputs.copy(appInForeground = false))
        if (!couldShow) unstash()

        status.report(
            PillStatusRules.standDown(
                mode = mode,
                granted = granted,
                everGranted = everGranted,
                attachRefused = refusalReported,
            ),
        )
        stateMachine.updateEnvironment(enabled = eligible, appInForeground = appInForeground.value)
    }

    companion object {
        private const val TAG = "PillController"

        /** How often the permission is re-read while the pill is on screen. */
        const val PERMISSION_POLL_MS = 2_000L
    }
}

/** The stand-down status as a pure decision. */
object PillStatusRules {
    fun standDown(
        mode: PillMode,
        granted: Boolean,
        everGranted: Boolean,
        attachRefused: Boolean,
    ): PillStandDown = when {
        mode != PillMode.BUILT_IN -> PillStandDown.NONE
        !granted && everGranted -> PillStandDown.PERMISSION_LOST
        attachRefused -> PillStandDown.ATTACH_REFUSED
        else -> PillStandDown.NONE
    }
}
