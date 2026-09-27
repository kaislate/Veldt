// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

/**
 * Whether Veldt currently holds the overlay permission (`Settings.canDrawOverlays`,
 * backed by `SYSTEM_ALERT_WINDOW`, spec §3).
 *
 * There is no permission-changed broadcast for this one — a plain function, checked fresh on
 * demand, rather than a cached `Flow`. Callers re-check it themselves at the moments spec §3
 * cares about: the settings screen's status row, and (a later task) re-reading it on
 * `ON_RESUME` in case the user granted or revoked it from outside the app.
 */
interface OverlayPermission {
    fun isGranted(): Boolean
}
