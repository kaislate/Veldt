// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM. [scrimAtFraction] against the constants [ArtBackdrop] draws with (read through
 * [backdropScrim], never restated in main code) and against the band positions its KDoc
 * documents for [scrimAtText].
 */
class ScrimAtFractionTest {

    private val eps = 1e-4f

    @Test fun `the ends are exactly the gradient's two stops, per theme`() {
        for (light in listOf(true, false)) {
            val scrim = backdropScrim(light)
            assertEquals(scrim.top, scrimAtFraction(light, 0f), eps)
            assertEquals(scrim.bottom, scrimAtFraction(light, 1f), eps)
        }
    }

    @Test fun `the drawn values - light 0_55 to 0_95, dark 0_35 to 0_80`() {
        assertEquals(0.55f, scrimAtFraction(true, 0f), eps)
        assertEquals(0.95f, scrimAtFraction(true, 1f), eps)
        assertEquals(0.35f, scrimAtFraction(false, 0f), eps)
        assertEquals(0.80f, scrimAtFraction(false, 1f), eps)
    }

    /** scrimAtText's KDoc: its 0.62 is "~60% down" dark's gradient and "~17% down" light's. */
    @Test fun `scrimAtText sits at the band positions ArtBackdrop documents`() {
        assertEquals(scrimAtText(false), scrimAtFraction(false, 0.60f), eps)
        assertEquals(scrimAtText(true), scrimAtFraction(true, 0.175f), eps)
    }

    /** The KDoc's other documented point: dark 0.73 is "~89% down" (0.35 + 0.45 * 0.844). */
    @Test fun `dark 0_73 is where the KDoc places it`() {
        assertEquals(0.73f, scrimAtFraction(false, 0.8444f), 1e-3f)
    }

    @Test fun `linear between the stops and clamped outside them`() {
        for (light in listOf(true, false)) {
            val s = backdropScrim(light)
            assertEquals((s.top + s.bottom) / 2, scrimAtFraction(light, 0.5f), eps)
            assertEquals(s.top, scrimAtFraction(light, -0.3f), eps)
            assertEquals(s.bottom, scrimAtFraction(light, 1.7f), eps)
        }
    }
}
