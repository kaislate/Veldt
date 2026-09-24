// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResumeCoordinator]'s registration lifecycle (task 4+5 review, item 3): a fake [NetworkReturn]
 * that counts LIVE registrations, so the exact bug the review found — a callback registered once
 * and never unregistered outside `PlaybackConnection.release`, which production never calls — is
 * directly reachable without a `MediaController`, unlike everything else in `PlaybackConnection`.
 */
class ResumeCoordinatorTest {

    /** Counts callbacks currently registered rather than ever registered. [current] answers a
     *  network chosen by the test; [fire] delivers it to whichever callback is live right now. */
    private class CountingNetworkReturn : NetworkReturn {
        var live = 0
            private set
        var current: Any? = null
        private var onAvailable: ((Any) -> Unit)? = null

        /** Never cleared by unregistering — see [fireStale]. */
        private var lastRegistered: ((Any) -> Unit)? = null

        /** Every callback ever registered, in arrival order, never cleared — lets a test invoke a
         *  SPECIFIC earlier registration directly (round 2, item c: a stale callback from a
         *  registration a later `arm()` has already superseded), not just "whatever's most recent"
         *  ([fireStale]). */
        val registrations = mutableListOf<(Any) -> Unit>()

        override fun current(): Any? = current

        override fun listen(onAvailable: (Any) -> Unit): () -> Unit {
            live++
            this.onAvailable = onAvailable
            lastRegistered = onAvailable
            registrations += onAvailable
            return {
                if (this.onAvailable != null) {
                    live--
                    this.onAvailable = null
                }
            }
        }

        fun fire(network: Any) = onAvailable?.invoke(network)

        /**
         * Delivers to the MOST RECENTLY registered callback even after it has been unregistered —
         * models a real `android.os.Handler` message that was already posted to the main looper
         * before `unregisterNetworkCallback` took effect. Unregistering removes the OS-level
         * callback going forward; it does not retract a message already in the queue.
         */
        fun fireStale(network: Any) = lastRegistered?.invoke(network)
    }

    private fun state(index: Int, mediaId: String?, playWhenReady: Boolean) =
        ResumeCoordinator.QueueState(index, mediaId, playWhenReady)

    @Test fun `arm registers exactly one callback, onReady unregisters it, and re-arming registers again`() {
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(), network)

        assertEquals(0, network.live)
        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, "m0", false) }, resume = {})
        assertEquals(1, network.live)

        coordinator.onReady()
        assertEquals(0, network.live)

        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, "m0", false) }, resume = {})
        assertEquals(1, network.live)
    }

    @Test fun `disarm also unregisters — covers STOP, a skip, and release`() {
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(), network)

        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, "m0", false) }, resume = {})
        assertEquals(1, network.live)

        coordinator.disarm()
        assertEquals(0, network.live)
    }

    @Test fun `a granted resume unregisters immediately, before the resume callback runs`() {
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(), network)
        var liveDuringResume = -1
        var resumed = false

        coordinator.arm(
            itemIndex = 0,
            mediaId = "m0",
            state = { state(0, "m0", false) },
            resume = { liveDuringResume = network.live; resumed = true },
        )
        assertEquals(1, network.live)

        network.fire("B") // a genuinely different network — the gate grants the resume
        assertTrue(resumed)
        assertEquals(0, liveDuringResume)
        assertEquals(0, network.live)
    }

    @Test fun `playWhenReady, not isPlaying, reaches the gate — a resume already in flight refuses a second one`() {
        // Task 4+5 review item 4. A granted resume unregisters immediately (item 3), but a REAL
        // ConnectivityManager delivers its callback through a Handler.post that may already be
        // queued before unregisterNetworkCallback takes effect -- fireStale models exactly that
        // race. The item this class actually cares about: does the SECOND, stale callback get
        // refused? It does, because by the time it runs, playWhenReady() is querying the state AT
        // THAT MOMENT, and the resume from B already set it to true — Player.isPlaying() would
        // still read false here (state is BUFFERING, not yet STATE_READY), and reading that as
        // "not resumed yet" would grant a second, wasted prepare()+play().
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(maxAttempts = 3), network)
        var resumeCalls = 0
        var playWhenReady = false

        coordinator.arm(
            itemIndex = 0,
            mediaId = "m0",
            state = { state(0, "m0", playWhenReady) },
            // The real prepare()+play() sets playWhenReady on the controller immediately, well
            // before STATE_READY — modelled here by flipping it the instant resume() runs.
            resume = { resumeCalls++; playWhenReady = true },
        )
        network.fire("B") // a genuine change: granted
        assertEquals(1, resumeCalls)
        assertEquals(0, network.live) // unregistered the instant the resume was granted

        network.fireStale("C") // the race: already in flight when the unregister above ran
        assertEquals("a resume already in flight must not be duplicated", 1, resumeCalls)
    }

    @Test fun `a callback that disarms the gate without granting a resume also unregisters`() {
        // Round 2, item a. The gate can disarm ITSELF — here, a mediaId mismatch — without ever
        // granting a resume. Before this fix the registration stayed live until the next
        // onReady/arm/disarm, even though nothing armed is left for it to watch for.
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(), network)
        var mediaId = "m0"

        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, mediaId, false) }, resume = {})
        assertEquals(1, network.live)

        mediaId = "different" // the queue moved on to a different item sitting at the same index
        network.fire("B") // a genuinely different network, but the gate now sees a mediaId mismatch
        assertEquals(
            "the gate disarmed itself without granting a resume, so nothing should still be registered",
            0,
            network.live,
        )
    }

    @Test fun `a stale callback from a registration a later arm has superseded is ignored`() {
        // Round 2, item c. Not the SAME registration firing twice (that is item 3's "granted resume
        // unregisters immediately" test, above) but TWO DIFFERENT registrations: arm() is called
        // again — a fresh pause re-arming before callback 1 ever ran — and callback 1 then fires
        // anyway, modelling a Handler message already queued before arm()'s own stop() unregistered
        // it. Unguarded, callback 1 would evaluate against the GATE'S CURRENT (registration 2)
        // state and its own stop() would tear down registration 2's still-live listen out from
        // under it.
        val network = CountingNetworkReturn().also { it.current = "A" }
        val coordinator = ResumeCoordinator(ResumeGate(), network)
        var resumeCalls = 0

        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, "m0", false) }, resume = { resumeCalls++ })
        val callback1 = network.registrations[0]

        coordinator.arm(itemIndex = 0, mediaId = "m0", state = { state(0, "m0", false) }, resume = { resumeCalls++ })
        assertEquals(2, network.registrations.size)
        assertEquals("arm()'s own stop() must have unregistered callback 1; only callback 2 is live", 1, network.live)

        callback1.invoke("Z") // the stale race: callback 1 fires anyway, with a genuinely new network
        assertEquals("a superseded registration must not grant a resume", 0, resumeCalls)
        assertEquals("callback 2's still-live registration must be untouched", 1, network.live)
    }
}
