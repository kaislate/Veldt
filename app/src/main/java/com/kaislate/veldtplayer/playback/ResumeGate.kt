// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

/**
 * The decision half of "resume a network-paused stream when a different network comes up"
 * (N2 task 5, carried gap 3). [ResumeCoordinator] owns the [android.net.ConnectivityManager]
 * plumbing (via [NetworkReturn]) and drives this class from [PlaybackConnection]'s `MediaController`
 * calls; this class only decides WHETHER a given network callback should trigger a resume, so the
 * decision is reachable from a plain JUnit test without a `Network`, a `Context`, or Robolectric.
 *
 * `network` handles are opaque `Any` here on purpose. The production caller always passes a real
 * `android.net.Network` (compared with `equals`, which `Network` implements value-wise), but this
 * class never needs to know that — it only ever compares one network handle to another.
 *
 * ### Why the immediate callback for the CURRENT network must not resume
 *
 * `ConnectivityManager.registerDefaultNetworkCallback` fires `onAvailable` immediately, synchronously
 * from the registering call, for whatever network is already the default — not only for a genuinely
 * new one. If that first callback counted as "the network came back", a stream that fails because
 * the SERVER (not the radio) is down would resume immediately, fail again, re-arm, get the same
 * immediate callback again, and so on for as long as the outage lasts: error -> pause -> resume,
 * tight-looping with no user action and no backoff. [arm] therefore records the network current at
 * pause time, and [onNetworkAvailable] only resumes on a network that differs from it — i.e. an
 * actual change, not the pre-existing one being reported back.
 *
 * ### Why a cap, and why only [onReady] resets it
 *
 * A flaky connection can hop between two networks (Wi-Fi <-> mobile data) many times while the
 * underlying outage — a wrong password, a dead server, a captive portal — is the real cause. Without
 * a bound, every hop would re-trigger `prepare()` + `play()` forever. The count is reset only by
 * reaching `STATE_READY`: a resume that never gets there does not refill the budget, so a genuinely
 * unrecoverable item still stops retrying after [maxAttempts].
 *
 * ### Why the armed item is identified by BOTH index and media id (task 4+5 review, item 5)
 *
 * Index alone is an accident waiting to happen: a queue replaced by [PlaybackConnection.playFrom]
 * while paused at index 0 leaves a NEW item sitting at the SAME index the gate armed on. Comparing
 * `currentMediaItem?.mediaId` as well means a coincidentally-matching index cannot masquerade as
 * "the queue didn't move" — a genuinely different item, at the same position, still disarms.
 *
 * ### Why [onNetworkAvailable] is told `playWhenReady`, not `isPlaying` (task 4+5 review, item 4)
 *
 * `Player.isPlaying()` requires `STATE_READY`, which a resume this class just granted has not
 * reached yet — it is still buffering. Checking `isPlaying` would read that legitimate buffering as
 * "not playing yet", and a network flip arriving mid-buffer would consume ANOTHER resume from the
 * cap for a resume that was already in flight and worked. `playWhenReady` is true the instant
 * something — the user, or this class's own resume — asked to play, buffering or not, which is
 * exactly "is a resume already underway".
 */
internal class ResumeGate(private val maxAttempts: Int = 3) {

    private var armed = false
    private var armedItemIndex = -1
    private var armedMediaId: String? = null

    /**
     * The network to compare the next callback against. Set by [arm] to whatever was current at
     * pause time (null if none), and advanced to the newly-accepted network on every resume, so a
     * gate left armed across a resume still requires the NEXT callback to differ from the one it
     * just acted on.
     */
    private var referenceNetwork: Any? = null

    /**
     * Resumes granted since the last [onReady]. Deliberately NOT reset by [disarm] or by re-arming:
     * a queue that keeps failing without ever reaching `STATE_READY` must not get a fresh budget
     * just because it was paused and armed again in between.
     */
    private var resumeCount = 0

    /** Call on every `PAUSE_IN_PLACE`: [itemIndex]/[mediaId] identify the paused item, [networkAtArm]
     *  is whatever [NetworkReturn.current] reported at that moment (null if there was no network at
     *  all). */
    fun arm(itemIndex: Int, mediaId: String?, networkAtArm: Any?) {
        armed = true
        armedItemIndex = itemIndex
        armedMediaId = mediaId
        referenceNetwork = networkAtArm
    }

    /**
     * Called from the network callback with the network that just became available, the queue's
     * current item index and media id, and whether a play is currently wanted (`playWhenReady`, not
     * `isPlaying` — see the class KDoc). Returns true exactly when the caller should `prepare()` +
     * `play()`.
     */
    fun onNetworkAvailable(network: Any, currentIndex: Int, currentMediaId: String?, playWhenReady: Boolean): Boolean {
        if (!armed) return false
        if (playWhenReady) {
            // Something else already resumed it (the user tapped play, or a resume already in
            // flight is buffering) — this pause is stale.
            disarm()
            return false
        }
        if (currentIndex != armedItemIndex || currentMediaId != armedMediaId) {
            // The queue moved on (skip, seek to another item, or a whole new queue replacing this
            // one) while this was armed — stale too.
            disarm()
            return false
        }
        if (network == referenceNetwork) {
            // The callback for the network already current when this armed — not a return. Stay
            // armed and keep waiting for an actual change.
            return false
        }
        if (resumeCount >= maxAttempts) {
            disarm()
            return false
        }
        resumeCount++
        referenceNetwork = network
        return true
    }

    /** Whether something is currently armed. [ResumeCoordinator] reads this after a callback that
     *  did not grant a resume, to tell "still waiting for a real change" (stay registered) apart
     *  from "the gate disarmed itself" — mediaId mismatch, `playWhenReady`, or the cap — (stop
     *  registering; task 4+5 review round 2, item a). */
    fun isArmed(): Boolean = armed

    /** Call when playback reaches `STATE_READY`: the resume worked, so disarm — nothing more to
     *  watch for until the next pause re-arms — and refill the budget. */
    fun onReady() {
        disarm()
        resumeCount = 0
    }

    /** Stops watching. Idempotent. Deliberately leaves [resumeCount] untouched — see its KDoc. */
    fun disarm() {
        armed = false
        armedItemIndex = -1
        armedMediaId = null
        referenceNetwork = null
    }
}
