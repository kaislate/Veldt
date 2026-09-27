// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import androidx.annotation.MainThread
import com.kaislate.veldtplayer.playback.PlaybackConnection
import javax.inject.Inject

/**
 * The transport commands the pill and its card can issue.
 *
 * Wisp sent these through its `MediaSessionBus`, which was two-way there. Veldt's bus is
 * one-way by contract (see [com.kaislate.veldtplayer.data.media.MediaSessionBus]), so the pill
 * READS the bus and COMMANDS through the app's one [PlaybackConnection]. A seam rather than the
 * connection itself so the button→command mapping in
 * [com.kaislate.veldtplayer.pill.ui.island.PillTransport] is testable with a recording fake.
 *
 * Main thread only, inherited from [PlaybackConnection]; every caller is a Compose click handler.
 */
interface PillCommands {
    @MainThread fun togglePlayPause()
    @MainThread fun next()
    @MainThread fun previous()
    @MainThread fun seekTo(positionMs: Long)
}

/** The real [PillCommands]: straight through to [PlaybackConnection]. */
class ConnectionPillCommands @Inject constructor(
    private val connection: PlaybackConnection,
) : PillCommands {
    override fun togglePlayPause() = connection.toggle()
    override fun next() = connection.next()
    override fun previous() = connection.previous()
    override fun seekTo(positionMs: Long) = connection.seekTo(positionMs)
}
