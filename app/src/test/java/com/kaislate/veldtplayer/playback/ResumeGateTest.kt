// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JUnit: [ResumeGate] is a pure decision object over opaque `Any` network handles (the
 * production caller passes a real `android.net.Network`; these tests use plain strings, which is
 * legitimate because the gate only ever compares them with `==`, never inspects them).
 */
class ResumeGateTest {

    @Test fun `an unarmed gate never resumes`() {
        val gate = ResumeGate()
        assertFalse(gate.onNetworkAvailable(network = "A", currentIndex = 0, isPlaying = false))
    }

    @Test fun `the immediate callback for the network already current at arm time does not resume, a different network does`() {
        // Review Focus 3: registerDefaultNetworkCallback fires onAvailable immediately, for the
        // CURRENT default network, the moment it is registered. Treating that first callback as a
        // "network returned" signal would resume into the very network the stream just failed on,
        // tight-looping error -> pause -> resume for as long as the server stays down.
        val gate = ResumeGate()
        gate.arm(itemIndex = 2, networkAtArm = "A")
        assertFalse(gate.onNetworkAvailable(network = "A", currentIndex = 2, isPlaying = false))
        assertTrue(gate.onNetworkAvailable(network = "B", currentIndex = 2, isPlaying = false))
    }

    @Test fun `resuming is refused and the gate disarms once the user has already started playing`() {
        val gate = ResumeGate()
        gate.arm(itemIndex = 1, networkAtArm = "A")
        assertFalse(gate.onNetworkAvailable(network = "B", currentIndex = 1, isPlaying = true))
        // Disarmed: an otherwise-valid network change no longer resumes.
        assertFalse(gate.onNetworkAvailable(network = "C", currentIndex = 1, isPlaying = false))
    }

    @Test fun `resuming is refused and the gate disarms once the queue has moved past the paused item`() {
        val gate = ResumeGate()
        gate.arm(itemIndex = 3, networkAtArm = "A")
        assertFalse(gate.onNetworkAvailable(network = "B", currentIndex = 4, isPlaying = false))
        // Disarmed: an otherwise-valid network change on the (no longer paused) item 3 no longer
        // resumes either.
        assertFalse(gate.onNetworkAvailable(network = "C", currentIndex = 3, isPlaying = false))
    }

    @Test fun `a cap of three resumes without onReady is enforced, and onReady resets the count`() {
        val gate = ResumeGate(maxAttempts = 3)
        gate.arm(itemIndex = 0, networkAtArm = null)
        assertTrue(gate.onNetworkAvailable(network = "B", currentIndex = 0, isPlaying = false))
        assertTrue(gate.onNetworkAvailable(network = "C", currentIndex = 0, isPlaying = false))
        assertTrue(gate.onNetworkAvailable(network = "D", currentIndex = 0, isPlaying = false))
        // The 4th resume in a row, with playback never having reached STATE_READY in between, is
        // refused by the cap.
        assertFalse(gate.onNetworkAvailable(network = "E", currentIndex = 0, isPlaying = false))

        gate.onReady()
        gate.arm(itemIndex = 0, networkAtArm = "E")
        assertTrue(gate.onNetworkAvailable(network = "F", currentIndex = 0, isPlaying = false))
    }

    @Test fun `arming with no known network resumes on the first network that becomes available`() {
        // No network at all when the pause happened (e.g. the app launched offline): there is no
        // "current network" to distinguish the return from, so the very first callback resumes.
        val gate = ResumeGate()
        gate.arm(itemIndex = 0, networkAtArm = null)
        assertTrue(gate.onNetworkAvailable(network = "A", currentIndex = 0, isPlaying = false))
    }

    @Test fun `disarm stops a subsequent network change from resuming`() {
        val gate = ResumeGate()
        gate.arm(itemIndex = 0, networkAtArm = "A")
        gate.disarm()
        assertFalse(gate.onNetworkAvailable(network = "B", currentIndex = 0, isPlaying = false))
    }
}
