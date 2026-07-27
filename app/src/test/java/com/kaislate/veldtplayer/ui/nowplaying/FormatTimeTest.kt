package com.kaislate.veldtplayer.ui.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrub readout is the one place in the app that renders a duration, and its
 * carry boundaries are the easy thing to get wrong: 59s -> 1:00 and 59:59 -> 1:00:00
 * both change the shape of the string, not just a digit.
 */
class FormatTimeTest {

    @Test fun `zero and negative positions read as zero, never as a blank or a minus`() {
        assertEquals("0:00", formatTime(0L))
        assertEquals("0:00", formatTime(-1L))
        // MediaItem duration is TIME_UNSET (Long.MIN_VALUE) before the track is prepared.
        assertEquals("0:00", formatTime(Long.MIN_VALUE))
    }

    @Test fun `seconds are zero-padded but minutes are not`() {
        assertEquals("0:05", formatTime(5_000L))
        assertEquals("0:59", formatTime(59_000L))
        assertEquals("1:00", formatTime(60_000L))
        assertEquals("3:07", formatTime(187_000L))
    }

    @Test fun `sub-second remainder truncates rather than rounding up`() {
        assertEquals("0:05", formatTime(5_999L))
        assertEquals("0:00", formatTime(999L))
    }

    @Test fun `past an hour the string gains an hours field and pads minutes`() {
        assertEquals("59:59", formatTime(3_599_000L))
        assertEquals("1:00:00", formatTime(3_600_000L))
        assertEquals("1:02:03", formatTime(3_723_000L))
        // A long DJ set: hours are not themselves padded.
        assertEquals("12:00:00", formatTime(43_200_000L))
    }
}
