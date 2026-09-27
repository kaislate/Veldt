// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guard for plan Review Focus 5: `PlaybackService` owns the pill, starts it in `onCreate`
 * and releases it FIRST in `onDestroy` — before `MediaSessionBus.reset()` and before the player
 * is torn down. The controller's own release path (overlay removed) is asserted behaviourally in
 * [PillControllerTest]; this pins that the service actually calls it, in that order. A service
 * test would need the whole Media3/Hilt graph, which the suite does not stand up.
 */
class PlaybackServicePillWiringTest {

    private fun serviceSource(): String {
        val rel = "src/main/java/com/kaislate/veldtplayer/playback/PlaybackService.kt"
        val file = listOf(File(rel), File("app/$rel"), File("../app/$rel")).firstOrNull { it.isFile }
            ?: error("cannot locate PlaybackService.kt from ${File(".").absolutePath}")
        return file.readText()
    }

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        // Up to the next override: enough to bound one method body.
        val next = source.indexOf("override fun", start + signature.length)
        return if (next < 0) source.substring(start) else source.substring(start, next)
    }

    @Test fun `onDestroy releases the pill before anything else`() {
        val destroy = body(serviceSource(), "override fun onDestroy()")
        val release = destroy.indexOf("pillController.release()")
        assertTrue("onDestroy must call pillController.release()", release >= 0)
        listOf("busAdapter?.detach()", "MediaSessionBus.reset()", "player?.release()").forEach {
            val at = destroy.indexOf(it)
            assertTrue("$it must come after the pill's release", at > release)
        }
    }

    @Test fun `onCreate starts the pill after the bus adapter is attached`() {
        val create = body(serviceSource(), "override fun onCreate()")
        val start = create.indexOf("pillController.start()")
        val adapter = create.indexOf("PlayerBusAdapter(")
        assertTrue("onCreate must call pillController.start()", start >= 0)
        assertTrue("the pill starts after the bus adapter", start > adapter && adapter >= 0)
    }
}
