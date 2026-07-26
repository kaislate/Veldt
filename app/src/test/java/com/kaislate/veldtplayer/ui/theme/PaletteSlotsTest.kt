package com.kaislate.veldtplayer.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteSlotsTest {

    private val red = Color(0xFFFF0000)
    private val green = Color(0xFF00FF00)
    private val fallback = Color(0xFF888888)

    @Test fun `slot returns the color at that index`() {
        assertEquals(red, PaletteSlots.slot(listOf(red, green), 0, fallback))
        assertEquals(green, PaletteSlots.slot(listOf(red, green), 1, fallback))
    }

    @Test fun `short lists repeat their last color instead of going blank`() {
        assertEquals(green, PaletteSlots.slot(listOf(red, green), 2, fallback))
        assertEquals(green, PaletteSlots.slot(listOf(red, green), 4, fallback))
    }

    @Test fun `empty list falls back rather than crashing`() {
        assertEquals(fallback, PaletteSlots.slot(emptyList(), 0, fallback))
        assertEquals(fallback, PaletteSlots.slot(emptyList(), 3, fallback))
    }

    @Test fun `slot count covers every swatch the extractor can produce`() {
        assertEquals(5, PaletteSlots.SLOT_COUNT)
    }
}
