// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held cover's geometry (CoverFlight.kt): the owner's round-3 "the album art should be able
 * to be grabbed more and stay under the finger".
 *
 * The fixture is a 1080 x 2340 host (the S21 FE) with an 886 px art slot and a 144 px mini-player
 * thumbnail near the bottom-left, roughly where they are on the device.
 */
class CoverFlightTest {

    private companion object {
        val HOST = Rect(0f, 0f, 1080f, 2340f)
        val ART = Rect(97f, 520f, 97f + 886f, 520f + 886f)
        val THUMB = Rect(36f, 2020f, 36f + 144f, 2020f + 144f)
        const val EPS = 0.01f
    }

    private fun assertRect(expected: Rect, actual: Rect, eps: Float = EPS) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
    }

    private fun assertOffset(expected: Offset, actual: Offset, eps: Float = EPS) {
        assertEquals("x", expected.x, actual.x, eps)
        assertEquals("y", expected.y, actual.y, eps)
    }

    // ------------------------------------------------------------------------------ pinning

    @Test fun `a grab on the cover pins the grabbed point in the cover's unit square`() {
        assertOffset(Offset(0.5f, 0.5f), grabPin(ART.center, ART))
        assertOffset(Offset(0f, 0f), grabPin(ART.topLeft, ART))
        val quarter = Offset(ART.left + ART.width * 0.25f, ART.top + ART.height * 0.75f)
        assertOffset(Offset(0.25f, 0.75f), grabPin(quarter, ART))
    }

    /** Off the cover — the transport under it, the mini-player's title beside it — the nearest
     *  point of the cover is grabbed, never a point outside it. */
    @Test fun `a grab off the cover pins the nearest point of it`() {
        val onTransport = Offset(ART.center.x, ART.bottom + 400f)
        assertOffset(Offset(0.5f, 1f), grabPin(onTransport, ART))
        val onMiniTitle = Offset(THUMB.right + 300f, THUMB.center.y)
        assertOffset(Offset(1f, 0.5f), grabPin(onMiniTitle, THUMB))
    }

    @Test fun `a degenerate cover pins its centre`() {
        assertOffset(Offset(0.5f, 0.5f), grabPin(Offset(10f, 10f), Rect.Zero))
    }

    @Test fun `the gap is the finger's distance from the grabbed point`() {
        val finger = Offset(ART.center.x, ART.bottom + 400f)
        val pin = grabPin(finger, ART)
        assertOffset(Offset(0f, 400f), pinnedGap(finger, ART, pin))
        assertOffset(Offset.Zero, pinnedGap(ART.center, ART, grabPin(ART.center, ART)))
    }

    @Test fun `the gap closes as the sheet travels, in either direction, and never on its own`() {
        assertEquals(1f, gapRemaining(0.3f, 0.3f), 0f)
        assertEquals(0.5f, gapRemaining(0.3f + GAP_CLOSE_FRACTION / 2f, 0.3f), EPS)
        assertEquals(0.5f, gapRemaining(0.3f - GAP_CLOSE_FRACTION / 2f, 0.3f), EPS)
        assertEquals(0f, gapRemaining(0.3f + GAP_CLOSE_FRACTION, 0.3f), EPS)
        assertEquals(0f, gapRemaining(1f, 0f), 0f)
    }

    // ------------------------------------------------------------------------------ scaling

    @Test fun `size runs from the art slot at 0 to the thumbnail at 1`() {
        assertEquals(ART.size, coverSize(ART.size, THUMB.size, 0f))
        assertEquals(THUMB.size, coverSize(ART.size, THUMB.size, 1f))
        assertEquals(515f, coverSize(ART.size, THUMB.size, 0.5f).width, EPS)
    }

    /** The grabbed point stays on the point it is placed at whatever the size — the cover
     *  shrinks ABOUT the finger, not towards a corner. */
    @Test fun `a rect scaled about a point keeps its pin on that point`() {
        val point = Offset(600f, 1200f)
        val pin = Offset(0.25f, 0.75f)
        for (size in listOf(Size(886f, 886f), Size(400f, 400f), Size(144f, 144f))) {
            val r = rectAboutPoint(point, pin, size)
            assertEquals(size.width, r.width, EPS)
            assertEquals(size.height, r.height, EPS)
            assertOffset(point, pinPoint(r, pin))
        }
    }

    // ------------------------------------------------------------------------------ clamping

    @Test fun `a rect inside the bounds is untouched`() {
        assertRect(ART, clampInto(ART, HOST))
    }

    @Test fun `a rect is moved, never resized, back inside the bounds on every side`() {
        val size = Size(400f, 400f)
        assertRect(Rect(Offset(0f, 100f), size), clampInto(Rect(Offset(-150f, 100f), size), HOST))
        assertRect(Rect(Offset(680f, 100f), size), clampInto(Rect(Offset(900f, 100f), size), HOST))
        assertRect(Rect(Offset(100f, 0f), size), clampInto(Rect(Offset(100f, -90f), size), HOST))
        assertRect(Rect(Offset(100f, 1940f), size), clampInto(Rect(Offset(100f, 2300f), size), HOST))
    }

    @Test fun `a rect larger than the bounds aligns to their start`() {
        val big = Rect(Offset(-50f, 10f), Size(2000f, 400f))
        val clamped = clampInto(big, HOST)
        assertEquals(0f, clamped.left, EPS)
        assertEquals(2000f, clamped.width, EPS)
    }

    // ------------------------------------------------------------------------------ the seat

    @Test fun `the seat blend is off until 0_85 and full at the collapsed end`() {
        assertEquals(0f, seatWeight(0f), 0f)
        assertEquals(0f, seatWeight(SEAT_BLEND_START), 0f)
        assertEquals(1f, seatWeight(1f), 0f)
        val mid = seatWeight((SEAT_BLEND_START + 1f) / 2f)
        assertTrue("was $mid", mid > 0f && mid < 1f)
    }

    // ------------------------------------------------------------------------------ held cover

    private fun held(
        finger: Offset,
        pin: Offset,
        gap: Offset = Offset.Zero,
        remaining: Float = 0f,
        fraction: Float,
    ): Rect = heldCoverRect(
        finger = finger,
        pin = pin,
        gap = gap,
        remaining = remaining,
        size = coverSize(ART.size, THUMB.size, fraction),
        bounds = HOST,
        path = lerp(ART, THUMB, fraction),
        fraction = fraction,
    )

    /** The owner's complaint, as an assertion: half-way down, the grabbed point is still under
     *  the thumb, where the fixed path had the thumb below the cover. */
    @Test fun `half way down the grabbed point is still under the finger, in x and y`() {
        val grab = ART.center
        val pin = grabPin(grab, ART)
        val finger = Offset(grab.x + 120f, grab.y + 800f)
        val r = held(finger, pin, fraction = 0.5f)
        assertOffset(finger, pinPoint(r, pin))
        assertEquals(coverSize(ART.size, THUMB.size, 0.5f).width, r.width, EPS)
        // The fixed path would have left the finger off the cover by now.
        assertTrue(!lerp(ART, THUMB, 0.5f).contains(finger))
    }

    /** First frame of any drag: the held rect IS the rect the cover already had — no jump, even
     *  with the touch slop folded into the gap and the grab off the cover. */
    @Test fun `the first frame of a hold reproduces the cover's rect exactly`() {
        val down = Offset(ART.center.x, ART.bottom + 300f)
        val atStart = down + Offset(0f, 24f) // the slop the finger moved before the drag began
        val pin = grabPin(down, ART)
        val gap = pinnedGap(atStart, ART, pin)
        assertRect(ART, held(atStart, pin, gap, remaining = 1f, fraction = 0f))
    }

    /** Dragging UP from the mini-player begins at fraction 1 — full seat weight — and must still
     *  start exactly on the thumbnail. */
    @Test fun `a hold that starts on the thumbnail starts exactly on it`() {
        val down = Offset(THUMB.right + 300f, THUMB.center.y)
        val pin = grabPin(down, THUMB)
        val gap = pinnedGap(down, THUMB, pin)
        assertRect(THUMB, held(down, pin, gap, remaining = 1f, fraction = 1f))
    }

    @Test fun `a held cover cannot leave the screen`() {
        val pin = Offset(0.5f, 0.5f)
        for (finger in listOf(Offset(-500f, 1000f), Offset(1500f, 1000f), Offset(540f, -400f))) {
            val r = held(finger, pin, fraction = 0.3f)
            assertTrue("$r", r.left >= HOST.left - EPS && r.right <= HOST.right + EPS)
            assertTrue("$r", r.top >= HOST.top - EPS && r.bottom <= HOST.bottom + EPS)
        }
    }

    /** Past 0.85 the held cover is pulled onto its path, and at the collapsed end it IS the
     *  thumbnail — wherever the finger is — so the swap to the real thumbnail is seamless. */
    @Test fun `near the collapsed end the held cover seats onto the thumbnail`() {
        val pin = Offset(0.5f, 0.5f)
        val finger = Offset(900f, 2200f) // off to the right of the thumbnail
        assertRect(THUMB, held(finger, pin, fraction = 1f))
        val before = held(finger, pin, fraction = SEAT_BLEND_START)
        assertOffset(finger, pinPoint(before, pin)) // still fully under the finger at 0.85
    }
}
