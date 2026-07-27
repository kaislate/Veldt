package com.kaislate.veldtplayer.ui.theme

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [Palette] cannot sample a `Config.HARDWARE` bitmap, and `allowHardware(false)` cannot
 * help: it governs only Coil's decoder, which `AlbumArtFetcher` bypasses by returning a
 * ready-made `DrawableResult`. So [ColorExtractor] must do the conversion itself.
 *
 * These tests assert the CONVERSION DECISION rather than the absence of a crash.
 * Robolectric's ShadowBitmap models `config` faithfully but does not enforce the
 * hardware read restriction — `getPixels` on a hardware bitmap throws on a real device
 * and does not here — so a "does not throw" test would pass with the guard removed and
 * would prove nothing.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class ColorExtractorHardwareBitmapTest {

    @Test fun `a hardware bitmap is converted to a software config Palette can read`() {
        val hardware = Bitmap.createBitmap(8, 8, Bitmap.Config.HARDWARE)
        val readable = ColorExtractor.toReadable(hardware)

        assertNotNull("conversion should produce a bitmap", readable)
        assertEquals(Bitmap.Config.ARGB_8888, readable!!.config)
    }

    @Test fun `a software bitmap is passed through without a needless copy`() {
        val software = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        assertSame(software, ColorExtractor.toReadable(software))
    }

    @Test fun `extract survives a hardware bitmap`() {
        assertNotNull(ColorExtractor.extract(Bitmap.createBitmap(8, 8, Bitmap.Config.HARDWARE)))
    }
}
