// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether Veldt Wisp (`com.kaislate.veldt`) is installed on this device right now.
 *
 * Spec §3: the built-in pill stands down automatically when Wisp is installed, so this needs
 * to stay current rather than be checked once at start — a [StateFlow], not a plain function,
 * so [PillVisibility.decide]'s caller can react to Wisp being installed or removed mid-session
 * without polling.
 */
interface WispPresence {
    val installed: StateFlow<Boolean>
}
