// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Why the built-in pill has stood down, if it has (spec §3: "status shown in Settings"). */
enum class PillStandDown {
    NONE,

    /** "Display over other apps" was granted earlier and has since been turned off. */
    PERMISSION_LOST,

    /** Android refused to attach the pill window although the permission reads as granted. */
    ATTACH_REFUSED;

    /** The line the Settings "Floating pill" section shows, or null for nothing to say. */
    val message: String?
        get() = when (this) {
            NONE -> null
            PERMISSION_LOST ->
                "The pill stood down: 'Display over other apps' was turned off for Veldt."
            ATTACH_REFUSED ->
                "Android refused to show the pill — check 'Display over other apps'."
        }
}

/**
 * The pill's stand-down status, process-wide.
 *
 * App-scoped because the writer ([PillController], owned by the playback service) and the
 * reader (the Settings view model) have no lifecycle in common. Not persisted: it describes the
 * running pill, and a new process starts with no stand-down to report.
 */
@Singleton
class PillStatus @Inject constructor() {
    private val _standDown = MutableStateFlow(PillStandDown.NONE)
    val standDown: StateFlow<PillStandDown> = _standDown.asStateFlow()

    fun report(value: PillStandDown) {
        _standDown.value = value
    }
}
