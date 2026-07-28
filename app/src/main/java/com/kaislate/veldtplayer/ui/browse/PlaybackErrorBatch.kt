// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long after an error Veldt keeps waiting for more before it says anything. Long
 * enough to swallow a whole skip-through of a dead queue (each failure is one prepare
 * attempt apart), short enough that a genuinely isolated failure still feels immediate.
 */
internal const val ERROR_BURST_WINDOW_MS = 800L

/**
 * Collapses a burst of playback errors into one message.
 *
 * `PlaybackConnection` emits one error per failed item as it skips through the queue, so
 * an unmounted SD card or a folder of stale MediaStore rows produces one error per track.
 * A snackbar is visible for seconds and `showSnackbar` suspends for that whole time, so
 * feeding the raw flow to it turns twenty dead files into twenty sequential toasts and
 * the better part of a minute of nagging. Errors landing within [windowMs] of each other
 * are therefore reported once, as a count.
 *
 * The pump goroutine drains the upstream into an unbounded channel immediately. That
 * matters: `PlaybackConnection._errors` is a `MutableSharedFlow` with a 4-item buffer
 * emitted into with `tryEmit`, so a collector that is slow — which a snackbar collector
 * inherently is — silently DROPS the overflow. Draining eagerly is what makes the count
 * accurate rather than capped at five.
 */
internal fun Flow<String>.batchPlaybackErrors(
    windowMs: Long = ERROR_BURST_WINDOW_MS,
): Flow<String> = flow {
    coroutineScope {
        val queue = Channel<String>(Channel.UNLIMITED)
        val pump = launch { collect { queue.send(it) } }
        try {
            while (true) {
                val batch = mutableListOf(queue.receive())
                // Each arrival re-opens the window, so a steady skip-through stays one
                // batch instead of fragmenting into a message per window.
                while (true) {
                    batch += withTimeoutOrNull(windowMs) { queue.receive() } ?: break
                }
                emit(summarizePlaybackErrors(batch))
            }
        } finally {
            pump.cancel()
        }
    }
}

/**
 * The one line a burst of [messages] is worth. Pure, so the wording is unit-testable
 * without a player.
 *
 * A repeat of the SAME message is reported once rather than counted: the failure that
 * repeats verbatim is "Couldn't connect to playback", and "Couldn't play 3 tracks" would
 * be an outright wrong description of it.
 */
internal fun summarizePlaybackErrors(messages: List<String>): String = when {
    messages.isEmpty() -> ""
    messages.distinct().size == 1 -> messages.first()
    else -> "Couldn't play ${messages.size} tracks"
}
