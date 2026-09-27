// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (data/overlay/OverlayRepositoryImplementation.kt), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill.overlay

import com.kaislate.veldtplayer.pill.domain.overlay.OverlayRepository
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * The production [OverlayRepository]: the seam between everything that decides whether the
 * pill should be up (`IslandStateMachine`) and the one class that talks to the window manager.
 * It adds a narrower vocabulary (show / hide / is it up) and nothing else.
 */
@ServiceScoped
class WindowOverlayRepository @Inject constructor(
    private val windows: OverlayWindowManager,
) : OverlayRepository {

    /**
     * The last instruction given, not an inspection of the window itself —
     * [OverlayWindowManager.showIsland] can decline (see its `showFailure`), and this still
     * turns true. Callers use it to avoid re-issuing an instruction already given.
     */
    private val visible = MutableStateFlow(false)

    override val isPillVisible: StateFlow<Boolean> = visible.asStateFlow()

    override fun showPill() {
        // Window first, flag second, in both methods — an observer woken by the flag then
        // finds the window already in the state the flag advertises.
        windows.showIsland()
        visible.value = true
    }

    override fun hidePill() {
        windows.hide()
        visible.value = false
    }
}
