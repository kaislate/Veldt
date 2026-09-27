// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (domain/overlay/OverlayRepository.kt), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill.domain.overlay

import kotlinx.coroutines.flow.StateFlow

/**
 * All the rest of the app is allowed to say about the pill: put it up, take it down,
 * and is it up right now.
 *
 * Kept as an interface purely so the decision-making above it can be exercised without
 * a window manager, a Looper or a device — [com.kaislate.veldtplayer.pill.overlay.IslandStateMachine]
 * is tested against a recording stand-in that implements exactly this and nothing else.
 * The real implementation, backed by the overlay window manager, arrives with the
 * overlay UI (a later task in P1.5c).
 *
 * [isPillVisible] is deliberately a `StateFlow` rather than a plain flag: callers need
 * both the value now and notice of it changing, and a single source for the two rules
 * out the pair drifting apart.
 */
interface OverlayRepository {
    val isPillVisible: StateFlow<Boolean>
    fun showPill()
    fun hidePill()
}
