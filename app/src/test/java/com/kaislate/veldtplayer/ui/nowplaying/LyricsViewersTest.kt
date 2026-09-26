// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM. The ordering these tests pin is the one navigation actually produces: the ENTERING
 * surface's effect runs before the LEAVING surface is disposed.
 */
class LyricsViewersTest {

    private val edges = mutableListOf<Boolean>()
    private val viewers = LyricsViewers { edges += it }
    private val pane = Any()
    private val fullScreen = Any()

    @Test fun `one viewer opening and closing fires exactly one edge each way`() {
        viewers.set(pane, true)
        viewers.set(pane, false)
        assertEquals(listOf(true, false), edges)
    }

    @Test fun `pane to full screen - the pane's late release does not hide lyrics`() {
        viewers.set(pane, true)
        viewers.set(fullScreen, true)
        viewers.set(pane, false)
        assertEquals(listOf(true), edges)
    }

    /** The case a bare boolean gets wrong: back to a now-playing that still shows its pane. */
    @Test fun `full screen back to a pane that is up - the route's late release does not hide lyrics`() {
        viewers.set(fullScreen, true)
        viewers.set(pane, true)
        viewers.set(fullScreen, false)
        assertEquals(listOf(true), edges)
    }

    @Test fun `full screen back to a now-playing with the pane down - lyrics end hidden`() {
        viewers.set(fullScreen, true)
        viewers.set(fullScreen, false)
        assertEquals(listOf(true, false), edges)
    }

    @Test fun `repeated claims and stray releases are idempotent`() {
        viewers.set(pane, false)
        viewers.set(pane, true)
        viewers.set(pane, true)
        viewers.set(fullScreen, false)
        viewers.set(pane, false)
        viewers.set(pane, false)
        assertEquals(listOf(true, false), edges)
    }
}
