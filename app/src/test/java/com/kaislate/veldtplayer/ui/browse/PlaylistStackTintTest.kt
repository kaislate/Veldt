// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The playlists tab's signature idiom, checked in BOTH colour schemes.
 *
 * The stack of receding records is the only reason this tab is not the bare list every other
 * player ships, and its first implementation was `palette.accent.copy(alpha = 0.20f)` painted
 * onto the list surface. Browse surfaces use the neutral palette, whose accent is a mid grey —
 * a visible step on a dark ground and approximately nothing on a light one. That is not a defect
 * a device pass finds and fixes; it is a colour that cannot be right in both, so the fix was to
 * stop deriving it from an alpha over an unknown ground, and this is the test that says so.
 *
 * Contrast RATIO, not a luminance delta: the two schemes sit at opposite ends of the luminance
 * range, so a fixed delta that is generous in light is impossible in dark. The ratio is the
 * scheme-independent question — "is this a step you can see".
 *
 * The thresholds are deliberately far below any text-contrast floor. These are decorative shapes
 * whose job is to read as *behind* the cover; a card that met 4.5:1 against the list would be a
 * bright bar, not a receding record. What is being pinned is that the step exists at all.
 */
class PlaylistStackTintTest {

    /** `ColorExtractor`'s neutral fallback accent — what every browse surface actually gets. */
    private val neutralAccent = Color(0xFF8A8A93)

    /** A strongly coloured palette, as Task 7's per-playlist extraction will supply. */
    private val vividAccent = Color(0xFFCC3355)

    private val dark = darkColorScheme()
    private val light = lightColorScheme()

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    // Straight through to production, roles included — the helper picks its own base, so a
    // mutation swapping a container role for `surface` lands in every assertion below.
    private fun near(scheme: ColorScheme, accent: Color) =
        playlistStackTint(scheme, accent, near = true)

    private fun far(scheme: ColorScheme, accent: Color) =
        playlistStackTint(scheme, accent, near = false)

    /**
     * The whole point. The old implementation passed this in dark and failed it in light, and
     * nothing in the suite could tell.
     */
    @Test fun `both cards stand off the list surface in the light scheme`() {
        val nearContrast = contrast(near(light, neutralAccent), light.surface)
        val farContrast = contrast(far(light, neutralAccent), light.surface)
        assertTrue("near card vs light surface was $nearContrast", nearContrast >= MIN_NEAR)
        assertTrue("far card vs light surface was $farContrast", farContrast >= MIN_FAR)
    }

    @Test fun `both cards stand off the list surface in the dark scheme`() {
        val nearContrast = contrast(near(dark, neutralAccent), dark.surface)
        val farContrast = contrast(far(dark, neutralAccent), dark.surface)
        assertTrue("near card vs dark surface was $nearContrast", nearContrast >= MIN_NEAR)
        assertTrue("far card vs dark surface was $farContrast", farContrast >= MIN_FAR)
    }

    /** And with a real cover's colour, which is what Task 7 will feed it. */
    @Test fun `a vivid palette also clears the floor in both schemes`() {
        listOf(light to "light", dark to "dark").forEach { (scheme, name) ->
            val nearContrast = contrast(near(scheme, vividAccent), scheme.surface)
            assertTrue("near card vs $name surface was $nearContrast", nearContrast >= MIN_NEAR)
        }
    }

    /**
     * The stack has to READ as a stack: the far card is the fainter of the two, in both schemes.
     * The container roles invert between schemes, so this is not automatic — it holds because the
     * far card takes both the smaller container step and the smaller tint.
     */
    @Test fun `the far card recedes behind the near one in both schemes`() {
        listOf(light to "light", dark to "dark").forEach { (scheme, name) ->
            val nearContrast = contrast(near(scheme, neutralAccent), scheme.surface)
            val farContrast = contrast(far(scheme, neutralAccent), scheme.surface)
            assertTrue(
                "in $name the far card ($farContrast) must be fainter than the near one ($nearContrast)",
                farContrast < nearContrast,
            )
        }
    }

    /**
     * Opaque, always. A translucent card is a card whose appearance depends on whatever is drawn
     * behind it — which is the property that made the original wrong, and the one thing a colour
     * that has to work in two schemes cannot afford.
     */
    @Test fun `the tint is opaque whatever it is given`() {
        assertEquals(1f, near(light, neutralAccent).alpha, 0f)
        assertEquals(1f, far(dark, vividAccent).alpha, 0f)
        assertEquals(1f, playlistStackTint(light, Color(0x00FFFFFF), near = true).alpha, 0f)
        assertEquals(1f, playlistStackTint(dark, Color(0x00FFFFFF), near = false).alpha, 0f)
    }

    /**
     * The near card is built on `surfaceContainerHighest` at [NEAR_CARD_TINT], the far one on
     * `surfaceContainerHigh` at [FAR_CARD_TINT] — the pairings themselves, not just their
     * consequences.
     *
     * The blend is deliberately re-derived here rather than read back from the result. Reading it
     * back is not possible (the function overrides the accent's alpha, so no input leaves the base
     * untouched), and the contrast floors above would be cleared by several other bases, so
     * without this a mutation swapping `surfaceContainerHighest` for `surface` — which is
     * precisely the edit that reinstates the original light-mode defect — would pass everything.
     * Restating the formula is the cost of pinning the ROLE, which is the part that was wrong.
     */
    @Test fun `each card is its own container role at its own tint`() {
        listOf(light to "light", dark to "dark").forEach { (scheme, name) ->
            assertEquals(
                "near card, $name",
                vividAccent.copy(alpha = NEAR_CARD_TINT).compositeOver(scheme.surfaceContainerHighest),
                playlistStackTint(scheme, vividAccent, near = true),
            )
            assertEquals(
                "far card, $name",
                vividAccent.copy(alpha = FAR_CARD_TINT).compositeOver(scheme.surfaceContainerHigh),
                playlistStackTint(scheme, vividAccent, near = false),
            )
        }
    }

    /**
     * The tints are read from production above, so a change to them changes every contrast figure
     * in this file — but a change that happened to keep clearing the floors would still slip
     * through unnoticed. So the values themselves are pinned to LITERALS here, the same way
     * `HEADER_ITEM_COUNT` had to be after a mutation proved the constant-against-itself form
     * pinned nothing.
     */
    @Test fun `the tints are the values the contrast figures were chosen for`() {
        assertEquals(0.18f, NEAR_CARD_TINT, 0f)
        assertEquals(0.10f, FAR_CARD_TINT, 0f)
    }

    private companion object {
        /** A step you can see. Nowhere near a text floor — these shapes must stay in the back. */
        const val MIN_NEAR = 1.30f
        const val MIN_FAR = 1.15f
    }
}
