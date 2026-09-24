// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

/**
 * Owns [ResumeGate] together with the [NetworkReturn] registration it needs to be told about a
 * network change at all (task 4+5 review, item 3).
 *
 * **Why this is its own class.** The obvious place for this bookkeeping is inline in
 * [PlaybackConnection], next to the `MediaController` calls it triggers — and the first version of
 * this task put it there. The registration lifecycle bug review found (a [NetworkReturn.listen]
 * callback registered once, ever, and never unregistered outside [PlaybackConnection.release],
 * which production never calls) was exactly the kind of thing that inline placement hides: nothing
 * in that code exercises the registration count, because nothing can construct a real
 * `MediaController` in this project's JVM suite. Pulling the register/unregister bookkeeping out
 * here, parameterised over plain data instead of `MediaController`, makes it independently
 * reachable from a plain fake — see `ResumeCoordinatorTest`.
 *
 * **The registration lifecycle.** [arm] registers a [NetworkReturn.listen] callback; [onReady] and
 * [disarm] both unregister it, and so does a resume actually firing. There is at most one live
 * registration at a time. [PlaybackConnection] calls [disarm] from every `onPlayerError` branch that
 * is not `PAUSE_IN_PLACE` (a resumed item that then hits `STOP`, e.g. a rejected password, must not
 * accept a later network change and auto-play into the same rejection again) and from
 * [PlaybackConnection.release]; [onReady] fires from `publish()` on `STATE_READY`.
 */
internal class ResumeCoordinator(
    private val gate: ResumeGate,
    private val network: NetworkReturn,
) {
    private var stopListening: (() -> Unit)? = null

    /**
     * Call on every `PAUSE_IN_PLACE`. [itemIndex]/[mediaId] identify the paused item (see
     * [ResumeGate]'s KDoc for why both). [state] is called fresh every time the network callback
     * fires — never captured here — since the callback can land long after this call returns and
     * the queue can have changed by then; [resume] runs (still on the main thread — see
     * [NetworkReturn]'s KDoc) exactly when [ResumeGate] decides a resume is due, and the
     * registration is torn down immediately before it runs.
     */
    fun arm(itemIndex: Int, mediaId: String?, state: () -> QueueState, resume: () -> Unit) {
        gate.arm(itemIndex, mediaId, network.current())
        // Every caller of arm() disarms first on any other outcome (see PlaybackConnection), so
        // nothing should already be listening — stop defensively rather than trust that blindly,
        // since a doubled registration would leak one NetworkCallback per re-arm.
        stop()
        stopListening = network.listen { n ->
            val s = state()
            if (gate.onNetworkAvailable(n, s.index, s.mediaId, s.playWhenReady)) {
                stop()
                resume()
            }
        }
    }

    /** Call when playback reaches `STATE_READY`. */
    fun onReady() {
        gate.onReady()
        stop()
    }

    /** Call on any outcome other than an armed pause — `STOP`, a skip, or teardown. Idempotent. */
    fun disarm() {
        gate.disarm()
        stop()
    }

    private fun stop() {
        stopListening?.invoke()
        stopListening = null
    }

    /** What [ResumeGate.onNetworkAvailable] needs at the moment a network callback actually fires,
     *  read fresh rather than captured at arm time — see [arm]. */
    data class QueueState(val index: Int, val mediaId: String?, val playWhenReady: Boolean)
}
