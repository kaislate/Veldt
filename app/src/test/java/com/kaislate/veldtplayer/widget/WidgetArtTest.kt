// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.graphics.Bitmap
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for a real [Bitmap] and a real Palette pass, as in `ColorExtractorTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetArtTest {

    private fun solid(argb: Int, w: Int, h: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }

    @Test fun `no cover is the neutral tones and no bitmap`() {
        val art = WidgetArt.prepare(null)
        assertSame(WidgetArt.NONE, art)
        assertNull(art.bitmap)
        assertEquals(WidgetTonePair.of(ArtSeed.NEUTRAL), art.tones)
    }

    @Test fun `a cover's tones are the ones ColorExtractor seeds from it`() {
        val cover = solid(0xFFD32F2F.toInt(), 600, 600)
        assertEquals(WidgetTonePair.of(ColorExtractor.seedOf(cover)), WidgetArt.prepare(cover).tones)
    }

    @Test fun `the carried bitmap is a small square whatever the cover's shape`() {
        for ((w, h) in listOf(1200 to 1200, 1200 to 800, 300 to 900, 64 to 64)) {
            val bmp = WidgetArt.prepare(solid(0xFF3050A0.toInt(), w, h)).bitmap
            assertNotNull("${w}x$h", bmp)
            bmp!!
            assertEquals("${w}x$h width", WidgetArt.SIZE_PX, bmp.width)
            assertEquals("${w}x$h height", WidgetArt.SIZE_PX, bmp.height)
            assertEquals(Bitmap.Config.ARGB_8888, bmp.config)
        }
    }
}
