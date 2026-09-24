// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The metered-network bitrate cap (owner decision: METERED networks only; one setting, values
 * 0/320/192/128; unmetered always original).
 *
 * The table goes through a real [StreamQuality] — its flow collection and its read of the meter —
 * not only through [effectiveMaxBitRate], so a [StreamQuality] that read the meter backwards or
 * forgot to collect the setting fails here too.
 *
 * **What the fake meter permits that the real one forbids.** A lambda answers the same thing every
 * time; `ConnectivityManager.isActiveNetworkMetered` can change between two calls (the resolver
 * asks once per open, so a Wi-Fi→LTE handover mid-queue legitimately changes the answer for the
 * next track) and answers `true` when there is no active network at all. Neither matters to this
 * function — it is a pure read at call time — which is why a constant fake is adequate here.
 */
class StreamQualityTest {

    private val scopes = mutableListOf<CoroutineScope>()

    /** Unconfined so the eager collector has delivered the flow's current value by the time the
     *  constructor returns; the table would otherwise race the collector. */
    private fun quality(metered: Boolean, cap: Int): StreamQuality {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return StreamQuality(MutableStateFlow(cap), { metered }, scope)
    }

    @After fun tearDown() = scopes.forEach { it.cancel() }

    @Test fun `the cap applies on metered networks only, and 0 means original`() {
        val rows = listOf(true, false).flatMap { metered ->
            listOf(0, 128, 192, 320).map { cap -> metered to cap }
        }
        assertEquals(
            listOf(
                Triple(true, 0, null),
                Triple(true, 128, 128),
                Triple(true, 192, 192),
                Triple(true, 320, 320),
                Triple(false, 0, null),
                Triple(false, 128, null),
                Triple(false, 192, null),
                Triple(false, 320, null),
            ),
            rows.map { (metered, cap) -> Triple(metered, cap, quality(metered, cap).currentMaxBitRate()) },
        )
    }

    @Test fun `a changed setting is seen by the next read`() {
        // The setting is collected, not sampled once at construction: changing it in Settings must
        // affect the next track without restarting the service.
        val cap = MutableStateFlow(0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val subject = StreamQuality(cap, { true }, scope)
        val before = subject.currentMaxBitRate()
        cap.value = 128
        assertEquals(listOf<Int?>(null, 128), listOf(before, subject.currentMaxBitRate()))
    }
}
