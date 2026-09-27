// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.replaygain

import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import com.kaislate.veldtplayer.data.replaygain.ReplayGainValues
import org.junit.Assert.assertEquals
import org.junit.Test

/** ReplayGain's decisions with exact numbers: which gain, how many dB, what linear factor. */
class ReplayGainMathTest {

    private val tagged = ReplayGainValues(trackGainDb = -6f, trackPeak = 0.5f, albumGainDb = -9f, albumPeak = 0.25f)

    private fun gain(
        values: ReplayGainValues? = tagged,
        mode: ReplayGainMode = ReplayGainMode.TRACK,
        album: Boolean = false,
        preamp: Float = 0f,
    ) = ReplayGainMath.linearGain(values, mode, album, preamp)

    @Test fun `dB to linear`() {
        assertEquals(1f, ReplayGainMath.dbToLinear(0f), 0f)
        assertEquals(0.5011872f, ReplayGainMath.dbToLinear(-6f), 1e-6f)
        assertEquals(0.3548134f, ReplayGainMath.dbToLinear(-9f), 1e-6f)
        assertEquals(1.9952623f, ReplayGainMath.dbToLinear(6f), 1e-6f)
        assertEquals(0.1f, ReplayGainMath.dbToLinear(-20f), 1e-7f)
    }

    @Test fun `track gain -6 dB is 0_501187`() {
        assertEquals(0.5011872f, gain(), 1e-6f)
    }

    @Test fun `album gain -9 dB is 0_354813`() {
        assertEquals(0.3548134f, gain(mode = ReplayGainMode.ALBUM, album = true), 1e-6f)
    }

    @Test fun `pre-amp adds to the tag - track -6 plus 3 is -3 dB, 0_707946`() {
        assertEquals(0.7079458f, gain(preamp = 3f), 1e-6f)
        assertEquals("-6 - 6 = -12 dB", 0.2511886f, gain(preamp = -6f), 1e-6f)
    }

    @Test fun `pre-amp is clamped to plus or minus 6 dB`() {
        assertEquals("+9 acts as +6: 0 dB", 1f, gain(preamp = 9f), 1e-6f)
        assertEquals("-10 acts as -6: -12 dB", 0.2511886f, gain(preamp = -10f), 1e-6f)
    }

    @Test fun `clipping prevention - peak times gain never exceeds 1`() {
        // +4 dB track gain (1.584893) on a 0.8 peak would reach 1.268: capped at 1/0.8 = 1.25.
        val hot = ReplayGainValues(trackGainDb = 4f, trackPeak = 0.8f, albumGainDb = null, albumPeak = null)
        assertEquals(1.25f, gain(hot), 1e-6f)
        // With a pre-amp too: +4 + 6 = +10 dB (3.162278) is still capped at 1.25.
        assertEquals(1.25f, gain(hot, preamp = 6f), 1e-6f)
        // A peak that leaves headroom does not limit: +4 dB on a 0.5 peak reaches 0.792 < 1.
        val quiet = hot.copy(trackPeak = 0.5f)
        assertEquals(1.5848932f, gain(quiet), 1e-6f)
        // No peak known: nothing to limit against.
        assertEquals(1.5848932f, gain(hot.copy(trackPeak = null)), 1e-6f)
    }

    @Test fun `the peak goes with the gain it was measured with`() {
        // Album chosen but only track values tagged: track gain AND track peak.
        val trackOnly = ReplayGainValues(trackGainDb = 6f, trackPeak = 0.8f, albumGainDb = null, albumPeak = 0.1f)
        assertEquals(1.25f, gain(trackOnly, mode = ReplayGainMode.ALBUM, album = true), 1e-6f)
        // Track chosen but only album values tagged: album gain AND album peak.
        val albumOnly = ReplayGainValues(trackGainDb = null, trackPeak = 0.1f, albumGainDb = -3f, albumPeak = 0.9f)
        assertEquals(0.7079458f, gain(albumOnly), 1e-6f)
    }

    @Test fun `missing tags play unchanged, pre-amp included`() {
        assertEquals(1f, gain(values = null, preamp = 6f), 0f)
        assertEquals(1f, gain(ReplayGainValues(null, 0.9f, null, 0.9f), preamp = 6f), 0f)
    }

    @Test fun `off is unity whatever the tags say`() {
        assertEquals(1f, gain(mode = ReplayGainMode.OFF, preamp = 6f), 0f)
    }

    // ---- which gain ----

    @Test fun `auto is album gain for an album played in order`() {
        val auto = ReplayGainMode.AUTO
        assertEquals(true, ReplayGainMath.useAlbumGain(auto, shuffle = false, "a", "a", "b"))
        assertEquals(true, ReplayGainMath.useAlbumGain(auto, shuffle = false, "a", null, "a"))
        assertEquals("shuffled", false, ReplayGainMath.useAlbumGain(auto, shuffle = true, "a", "a", "a"))
        assertEquals("a lone track of its album", false, ReplayGainMath.useAlbumGain(auto, false, "a", "b", "c"))
        assertEquals("a single-item queue", false, ReplayGainMath.useAlbumGain(auto, false, "a", null, null))
        assertEquals("unknown album", false, ReplayGainMath.useAlbumGain(auto, false, null, null, null))
    }

    @Test fun `track and album modes ignore the queue, off never uses album`() {
        assertEquals(false, ReplayGainMath.useAlbumGain(ReplayGainMode.TRACK, false, "a", "a", "a"))
        assertEquals(true, ReplayGainMath.useAlbumGain(ReplayGainMode.ALBUM, true, "a", "b", "c"))
        assertEquals(false, ReplayGainMath.useAlbumGain(ReplayGainMode.OFF, false, "a", "a", "a"))
    }

    // ---- lookahead plan ----

    @Test fun `the plan is the current item and the next, with their queue neighbours`() {
        val ids = listOf("s:1", "s:2", "s:3", "s:4")
        assertEquals(
            listOf(
                ReplayGainPlan.Target("s:2", "s:1", "s:3"),
                ReplayGainPlan.Target("s:3", "s:2", "s:4"),
            ),
            ReplayGainPlan.targets(ids, currentIndex = 1, nextIndex = 2),
        )
        assertEquals(
            "the last item, no next",
            listOf(ReplayGainPlan.Target("s:4", "s:3", null)),
            ReplayGainPlan.targets(ids, currentIndex = 3, nextIndex = -1),
        )
        assertEquals(
            "repeat one: next is itself",
            listOf(ReplayGainPlan.Target("s:1", null, "s:2")),
            ReplayGainPlan.targets(ids, currentIndex = 0, nextIndex = 0),
        )
        assertEquals("an empty queue", emptyList<ReplayGainPlan.Target>(), ReplayGainPlan.targets(emptyList(), -1, -1))
    }
}
