// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Virtual-time tests for the debounce policy. Every assertion that could be satisfied by a
 * *leading*-edge implementation is paired with one that records `currentTime`, because the three
 * count-only cases in the brief are all satisfied by leading-edge and by trailing-edge alike —
 * a count of 1 does not say WHEN the 1 happened, and "trailing" was the whole requirement.
 */
@OptIn(ExperimentalCoroutinesApi::class) // TestScope.currentTime
class ScanTriggerTest {

    // ---- the brief's three cases (counts) -------------------------------------------------

    @Test
    fun `a burst inside the window produces exactly one scan`() = runTest {
        val src = flow { repeat(20) { emit(it.toLong()); delay(50) } }
        val out = ScanTrigger.coalesce(src, windowMs = 2_000).toList()
        assertEquals(1, out.size)
    }

    @Test
    fun `events separated by more than the window produce one scan each`() = runTest {
        val src = flow { emit(0L); delay(3_000); emit(1L) }
        assertEquals(2, ScanTrigger.coalesce(src, windowMs = 2_000).toList().size)
    }

    @Test
    fun `a single event still produces a scan`() = runTest {
        assertEquals(1, ScanTrigger.coalesce(flowOf(0L), windowMs = 2_000).toList().size)
    }

    // ---- the cases the counts above cannot distinguish (timing) ---------------------------

    /**
     * The discriminating test. A leading-edge coalescer passes all three cases above and this
     * one fails on it: the scan must land one window AFTER the last notification of the burst,
     * not on the first. Firing on the first is precisely the bug the debounce exists to prevent
     * — MediaStore's first notification arrives before the files it is telling us about have
     * finished being indexed, so a leading-edge scan reads a half-written library.
     */
    @Test
    fun `the scan fires one window after the last event, not on the first`() = runTest {
        val stamps = mutableListOf<Long>()
        val src = flow {
            repeat(20) { emit(it.toLong()); delay(50) } // last emit at t=950
            delay(10_000)                               // burst over; the source stays open
        }
        ScanTrigger.coalesce(src, windowMs = 2_000).collect { stamps += currentTime }
        assertEquals(listOf(2_950L), stamps)
    }

    /**
     * Pins the trade-off in the trailing window rather than leaving it in prose: while
     * notifications keep arriving faster than the window, NO scan runs. Six seconds of unbroken
     * churn is still one scan, and it lands 2s after the churn stops. This is deliberate (a scan
     * mid-copy would read a partial album) but it is also the behaviour that would surprise
     * someone, so it is asserted, not described.
     */
    @Test
    fun `an unbroken stream defers the scan until the churn stops`() = runTest {
        val stamps = mutableListOf<Long>()
        val src = flow {
            repeat(120) { emit(it.toLong()); delay(50) } // 6s of churn, last emit at t=5950
            delay(10_000)
        }
        ScanTrigger.coalesce(src, windowMs = 2_000).collect { stamps += currentTime }
        assertEquals(listOf(7_950L), stamps)
    }

    /**
     * The reason `a single event still produces a scan` cannot be read as a timing claim: a
     * source that ENDS flushes its pending event immediately instead of waiting out the window.
     * `flowOf(0L)` completes at t=0, so that test would pass on a leading-edge implementation
     * for a reason that has nothing to do with the leading edge.
     */
    @Test
    fun `a source that ends inside the window flushes its pending scan`() = runTest {
        val stamps = mutableListOf<Long>()
        ScanTrigger.coalesce(flow { emit(0L); delay(100) }, windowMs = 2_000)
            .collect { stamps += currentTime }
        assertEquals(listOf(100L), stamps)
    }
}
