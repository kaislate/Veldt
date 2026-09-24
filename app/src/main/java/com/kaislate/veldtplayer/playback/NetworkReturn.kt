// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

/**
 * [PlaybackConnection]'s seam onto `android.net.ConnectivityManager`, so [ResumeGate]'s wiring is
 * reachable from a test double instead of a real system service.
 *
 * Network handles cross this interface as `Any` for the same reason [ResumeGate] takes them that
 * way: the production implementation hands out real `android.net.Network` instances (which compare
 * equal by value), while a fake for a Robolectric or JVM test can hand out anything comparable.
 *
 * **Threading.** `ConnectivityManager.NetworkCallback` methods run on a `ConnectivityManager`
 * internal thread, never the main thread. [PlaybackConnection] talks to a `MediaController`, whose
 * methods are main-thread only (see its class KDoc), so the production [listen] implementation posts
 * to the main looper before invoking the callback it was given — callers must NOT re-post themselves.
 */
interface NetworkReturn {

    /** The current default network, or null if there is none right now. */
    fun current(): Any?

    /**
     * Starts watching for a network becoming available, invoking [onAvailable] with it — on the main
     * thread, see the class KDoc — every time, including once immediately for whatever network is
     * already current (this is `registerDefaultNetworkCallback`'s real behaviour, not a quirk of the
     * fake; see [ResumeGate] for why that immediate callback must not by itself trigger a resume).
     *
     * Returns a function that stops watching. Calling it is mandatory once the caller no longer
     * needs updates (resumed, disarmed, or released) — an un-stopped callback holds the
     * `ConnectivityManager` singleton's registration and keeps firing for the life of the process.
     */
    fun listen(onAvailable: (Any) -> Unit): () -> Unit

    companion object {
        /** Never reports a network and never calls back. For tests that construct
         *  [PlaybackConnection] without exercising network-return behaviour. */
        val NONE: NetworkReturn = object : NetworkReturn {
            override fun current(): Any? = null
            override fun listen(onAvailable: (Any) -> Unit): () -> Unit = {}
        }
    }
}
