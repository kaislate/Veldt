// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

/**
 * The decision half of "resume a network-paused stream when a different network comes up"
 * (N2 task 5, carried gap 3). [PlaybackConnection] owns the [android.net.ConnectivityManager]
 * plumbing (via [NetworkReturn]) and the `MediaController` calls; this class only decides WHETHER
 * a given network callback should trigger a resume, so the decision is reachable from a plain JUnit
 * test without a `Network`, a `Context`, or Robolectric.
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
 */
internal class ResumeGate(private val maxAttempts: Int = 3) {

    private var armed = false
    private var armedItemIndex = -1

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

    /** Call on every `PAUSE_IN_PLACE`: [itemIndex] is the paused item, [networkAtArm] is whatever
     *  [NetworkReturn.current] reported at that moment (null if there was no network at all). */
    fun arm(itemIndex: Int, networkAtArm: Any?) {
        armed = true
        armedItemIndex = itemIndex
        referenceNetwork = networkAtArm
    }

    /**
     * Called from the network callback with the network that just became available, the queue's
     * current item index, and whether it is currently playing. Returns true exactly when the caller
     * should `prepare()` + `play()`.
     */
    fun onNetworkAvailable(network: Any, currentIndex: Int, isPlaying: Boolean): Boolean {
        if (!armed) return false
        if (isPlaying) {
            // Something else already resumed it (the user tapped play) — this pause is stale.
            disarm()
            return false
        }
        if (currentIndex != armedItemIndex) {
            // The queue moved on (skip, seek to another item) while this was armed — stale too.
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

    /** Call when playback reaches `STATE_READY`: the resume worked, so refill the budget. */
    fun onReady() {
        resumeCount = 0
    }

    /** Stops watching. Idempotent. */
    fun disarm() {
        armed = false
        armedItemIndex = -1
        referenceNetwork = null
    }
}
