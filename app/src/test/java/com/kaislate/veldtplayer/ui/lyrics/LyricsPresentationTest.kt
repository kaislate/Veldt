// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.lifecycle.Lifecycle
import com.kaislate.veldtplayer.data.lyrics.LyricsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: the pure halves of `LyricsContent` — attribution text and the auto-follow timer. */
class LyricsPresentationTest {

    /** TOTAL over the enum: a new source added without a footer string fails here, not on screen. */
    @Test fun `every source has exactly its spec attribution`() {
        val expected = mapOf(
            LyricsSource.SIDECAR to "Lyrics from the .lrc file",
            LyricsSource.EMBEDDED to "Lyrics from the file's tags",
            LyricsSource.SERVER to "Lyrics from your server",
            LyricsSource.LRCLIB to "Lyrics from LRCLIB",
        )
        assertEquals(LyricsSource.entries.toSet(), expected.keys)
        LyricsSource.entries.forEach { assertEquals(expected.getValue(it), attribution(it)) }
    }

    @Test fun `never suspended follows`() {
        assertTrue(shouldAutoFollow(nowMs = 1_000L, suspendedUntilMs = 0L))
    }

    @Test fun `a drag in progress suspends indefinitely`() {
        assertFalse(shouldAutoFollow(nowMs = Long.MAX_VALUE - 1, suspendedUntilMs = FOLLOW_SUSPENDED_WHILE_DRAGGING))
    }

    @Test fun `a finished drag suspends for exactly 4 seconds`() {
        val released = 10_000L
        val until = suspendFollowAfterDrag(released)
        assertEquals(14_000L, until)
        assertFalse(shouldAutoFollow(released, until))
        assertFalse(shouldAutoFollow(released + 3_999L, until))
        assertTrue("the deadline itself counts as expired", shouldAutoFollow(released + 4_000L, until))
        assertTrue(shouldAutoFollow(released + 60_000L, until))
    }

    @Test fun `the suspension constant is the spec's 4 s`() {
        assertEquals(4_000L, FOLLOW_SUSPEND_MS)
    }

    @Test fun `a blank synced line renders as the instrumental mark, others verbatim`() {
        assertEquals("♪", lineLabel(""))
        assertEquals("♪", lineLabel("   "))
        assertEquals("hello  world", lineLabel("hello  world"))
    }

    /** TOTAL over Lifecycle.State x wanted: only STARTED/RESUMED with lyrics up may hold a claim. */
    @Test fun `a viewer claim needs lyrics up AND a started lifecycle`() {
        val allowed = Lifecycle.State.entries
            .flatMap { state -> listOf(true, false).map { wanted -> state to wanted } }
            .filter { (state, wanted) -> lyricsClaimActive(wanted, state) }
            .toSet()
        assertEquals(setOf(Lifecycle.State.STARTED to true, Lifecycle.State.RESUMED to true), allowed)
    }

    @Test fun `region top fraction - measured, clamped, and unmeasured reads as the top`() {
        assertEquals(0.25f, regionTopFraction(500f, 2000f), 1e-6f)
        assertEquals(0f, regionTopFraction(-40f, 2000f), 1e-6f)
        assertEquals(1f, regionTopFraction(2500f, 2000f), 1e-6f)
        assertEquals(0f, regionTopFraction(500f, 0f), 1e-6f)
    }
}
