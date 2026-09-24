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
 * [disarm] both unregister it, and so does a callback that leaves the gate no longer armed —
 * whether by granting a resume or by disarming itself (round 2, item a: a mediaId mismatch,
 * `playWhenReady`, or the cap all disarm [ResumeGate] without going through [onReady] or [disarm]).
 * There is at most one live registration at a time.
 *
 * [PlaybackConnection] calls [disarm] from every `onPlayerError` branch that is not
 * `PAUSE_IN_PLACE` (a resumed item that then hits `STOP`, e.g. a rejected password, must not accept
 * a later network change and auto-play into the same rejection again), from user navigation that
 * moves off the paused item (`next`, `previous`, `skipToQueueIndex` — round 2, item b), and from
 * [PlaybackConnection.release]; [onReady] fires from `publish()` on `STATE_READY`.
 *
 * ### The stale-callback race (round 2, item c)
 *
 * A REAL `ConnectivityManager` delivers its callback through `Handler.post`, and unregistering does
 * not retract a message already sitting in that queue. Two registrations can therefore both have a
 * message in flight at once: [arm] called twice in a row (a resume that itself failed quickly,
 * re-arming before the FIRST registration's already-queued message ever ran) leaves that first
 * message pointing at a closure that still shares this object's single [gate] and [stopListening]
 * field. If it ran unguarded, it would evaluate against `gate`'s CURRENT (second-registration) state
 * — matching an item/network relationship the first registration never armed on — and its own
 * `stop()` would tear down the SECOND, still-live registration out from under it. [generation] is
 * the guard: each [arm] mints a new one, the registered closure captures the value it was minted
 * with, and a closure whose captured value no longer matches [generation] when it runs knows a later
 * [arm] has superseded it and does nothing at all.
 */
internal class ResumeCoordinator(
    private val gate: ResumeGate,
    private val network: NetworkReturn,
) {
    private var stopListening: (() -> Unit)? = null

    /** Bumped by every [arm]; see the class KDoc's stale-callback section. */
    private var generation = 0

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
        val myGeneration = ++generation
        stopListening = network.listen { n ->
            if (myGeneration != generation) {
                // A later arm() has already superseded this registration (see the class KDoc) —
                // this closure belongs to a registration that, as far as this object is concerned,
                // no longer exists. Touching `gate` or `stopListening` here would act on state a
                // DIFFERENT, still-live registration owns.
                return@listen
            }
            val s = state()
            if (gate.onNetworkAvailable(n, s.index, s.mediaId, s.playWhenReady)) {
                stop()
                resume()
            } else if (!gate.isArmed()) {
                // The gate disarmed itself without granting a resume (round 2, item a) — a mediaId
                // mismatch, playWhenReady already true, or the cap. Without this, the registration
                // would stay live until the next onReady/arm/disarm even though nothing armed is
                // left to watch for.
                stop()
            }
        }
    }

    /** Call when playback reaches `STATE_READY`. */
    fun onReady() {
        gate.onReady()
        stop()
    }

    /** Call on any outcome other than an armed pause — `STOP`, a skip, user navigation off the
     *  paused item, or teardown. Idempotent. */
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
