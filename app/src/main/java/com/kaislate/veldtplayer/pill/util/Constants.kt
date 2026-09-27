// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (util/Constants.kt), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill.util

/**
 * Constants shared by the pill's decision core.
 *
 * Wisp's [com.kaislate.veldt.util.Constants] also carried a notification channel id/id pair
 * for its own foreground service; Veldt's pill is hosted by the existing `mediaPlayback`
 * foreground service (no second one), so those are dropped here rather than ported.
 */
object Constants {
    const val INACTIVITY_TIMEOUT_MS = 25_000L   // default; overridden by the hide-delay setting
}
