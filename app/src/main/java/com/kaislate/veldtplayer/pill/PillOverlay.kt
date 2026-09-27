// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import kotlinx.coroutines.flow.StateFlow

/**
 * One refused attach of the pill window.
 *
 * Deliberately NOT a data class: two refusals with the same reason are still two events, and a
 * `StateFlow` drops a value equal to the one it holds — so a second identical refusal would
 * otherwise never reach [PillController] and the pill would be left believed-shown.
 */
class ShowFailure(val reason: String) {
    override fun toString(): String = "ShowFailure($reason)"
}

/**
 * What [PillController] needs from the overlay window beyond show/hide (which the ported
 * `IslandStateMachine` drives through `OverlayRepository`): the refusal signal, the stash
 * gesture, and a direct [hide] for teardown. Implemented by
 * [com.kaislate.veldtplayer.pill.overlay.OverlayWindowManager]; a seam so the controller's
 * decisions are testable with a fake.
 */
interface PillOverlay {
    /** The last refused attach, or null once an attach has succeeded. */
    val showFailure: StateFlow<ShowFailure?>

    /** Called when the user flicks the collapsed pill toward its edge. */
    var onStashRequested: (() -> Unit)?

    /** Removes the pill (and any expanded panel) window now. Idempotent. Main thread. */
    fun hide()
}
