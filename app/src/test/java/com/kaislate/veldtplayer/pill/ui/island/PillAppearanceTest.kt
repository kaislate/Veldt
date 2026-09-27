// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.pill.ui.island

import com.kaislate.veldtplayer.pill.util.IslandPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `pill_*` settings → the parameters the ported composables take. */
class PillAppearanceTest {

    private fun appearance(
        anchor: IslandPosition = IslandPosition.TOP_CENTER,
        widthDp: Int = 160,
        waveStyle: String = "wisptrail",
        waveColor: String = "accent-light",
        artCrossfade: Boolean = true,
        transportButtons: String = "off",
    ) = PillAppearance.from(anchor, widthDp, waveStyle, waveColor, artCrossfade, transportButtons)

    @Test fun `transport buttons off means no controls on the pill`() {
        val a = appearance(transportButtons = "off")
        assertFalse(a.showPillControls)
        assertEquals(PillArrangement.TEXT_ONLY, PillLayout.arrangementFor(a.showPillControls, a.pillControlPosition))
    }

    @Test fun `each transport set shows controls and reaches buttonsFor unchanged`() {
        val expected = mapOf(
            "play" to listOf(PillButton.PLAY_PAUSE),
            "play-next" to listOf(PillButton.PLAY_PAUSE, PillButton.NEXT),
            "prev-play-next" to listOf(PillButton.PREVIOUS, PillButton.PLAY_PAUSE, PillButton.NEXT),
        )
        val actual = expected.keys.associateWith { key ->
            val a = appearance(transportButtons = key)
            assertTrue("$key must show controls", a.showPillControls)
            PillLayout.buttonsFor(a.pillControlSet)
        }
        assertEquals(expected, actual)
    }

    @Test fun `controls sit on the right, Wisp's default, since Veldt has no position key`() {
        val a = appearance(transportButtons = "play-next")
        assertEquals("right", a.pillControlPosition)
        assertEquals(PillArrangement.CONTROLS_RIGHT, PillLayout.arrangementFor(a.showPillControls, a.pillControlPosition))
    }

    @Test fun `art crossfade on is Wisp's 1000ms, off cuts instantly`() {
        assertEquals(listOf(1000, 0), listOf(appearance(artCrossfade = true).crossfadeMs, appearance(artCrossfade = false).crossfadeMs))
    }

    @Test fun `anchor, width, wave style and wave colour pass straight through`() {
        val a = appearance(
            anchor = IslandPosition.BOTTOM_LEFT, widthDp = 240, waveStyle = "mercury", waveColor = "white",
        )
        assertEquals(
            listOf<Any>(IslandPosition.BOTTOM_LEFT, 240, "mercury", "white"),
            listOf<Any>(a.position, a.pillTextWidthDp, a.waveStyle, a.waveColorMode),
        )
    }

    @Test fun `parameters with no Veldt key are pinned to Wisp's shipped defaults`() {
        val a = appearance()
        assertEquals(
            listOf<Any>(400, 40, "bar", true, false),
            listOf<Any>(a.panelWidthDp, a.topOffsetDp, a.thumbShape, a.consume, a.vibrant),
        )
    }

    @Test fun `the pre-emission default is the spec section 4 defaults`() {
        assertEquals(
            appearance(IslandPosition.TOP_CENTER, 160, "wisptrail", "accent-light", true, "off"),
            PillAppearance.DEFAULT,
        )
    }
}
