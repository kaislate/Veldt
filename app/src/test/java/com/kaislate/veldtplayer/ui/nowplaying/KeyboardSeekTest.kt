// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardSeekTest {

    private val step = 5_000L
    private val duration = 200_000L

    @Test fun `a forward press advances by one step`() {
        assertEquals(65_000L, keyboardSeekTarget(60_000L, step, duration))
    }

    @Test fun `a back press retreats by one step`() {
        assertEquals(55_000L, keyboardSeekTarget(60_000L, -step, duration))
    }

    @Test fun `stepping back near the start lands on zero, never below it`() {
        assertEquals(0L, keyboardSeekTarget(2_000L, -step, duration))
    }

    @Test fun `stepping forward near the end lands on the duration, never past it`() {
        assertEquals(duration, keyboardSeekTarget(198_000L, step, duration))
    }

    @Test fun `a zero-length track collapses every press onto zero`() {
        assertEquals(0L, keyboardSeekTarget(0L, step, 0L))
    }
}
