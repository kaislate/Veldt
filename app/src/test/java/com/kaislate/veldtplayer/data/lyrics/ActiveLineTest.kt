// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveLineTest {

    private val lines = listOf(
        LyricLine(1000, "a"),
        LyricLine(2000, "b"),
        LyricLine(3000, "c"),
    )

    @Test fun `before the first line is -1`() {
        assertEquals(-1, activeLineIndex(lines, 500))
    }

    @Test fun `exactly at a timestamp selects that line`() {
        assertEquals(1, activeLineIndex(lines, 2000))
    }

    @Test fun `between two timestamps selects the previous line`() {
        assertEquals(0, activeLineIndex(lines, 1999))
    }

    @Test fun `after the last line selects the last line`() {
        assertEquals(2, activeLineIndex(lines, 999_999))
    }

    @Test fun `an empty list is always -1`() {
        assertEquals(-1, activeLineIndex(emptyList(), 5000))
    }
}
