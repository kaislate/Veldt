// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Veldt itself is currently in the foreground (`ProcessLifecycleOwner` >= `STARTED`).
 *
 * Feeds [PillVisibility.decide]'s `appInForeground` gate and, downstream, the ported
 * `IslandStateMachine.updateEnvironment`'s `appInForeground` gate (Task 1). Plan Review Focus
 * 1 is why the threshold is `STARTED` and not `RESUMED`: the pill must never show while any
 * Veldt activity is started, including through `MainActivity`'s recreate on rotation, and a
 * configuration change's brief pause/resume around that teardown-and-rebuild must not read as
 * "Veldt left the foreground" for the length of a frame.
 */
interface AppForeground {
    val inForeground: StateFlow<Boolean>
}
