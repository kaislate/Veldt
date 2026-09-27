// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import dagger.Lazy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pill's command seam must not build the app's `PlaybackConnection` (an in-process
 * MediaController) just because the playback service started — only when a control is used.
 */
class ConnectionPillCommandsTest {

    @Test fun `constructing the commands does not resolve the connection`() {
        var resolved = 0
        ConnectionPillCommands(Lazy { resolved++; error("resolved eagerly") })
        assertEquals(0, resolved)
    }
}
