// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import android.graphics.Bitmap
import com.kaislate.veldtplayer.ui.theme.hct.Hct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ColorExtractor.seedOf] walks a real [Palette][androidx.palette.graphics.Palette], which
 * needs a real [Bitmap] — Robolectric stays for that reason, same as
 * [ColorExtractorHardwareBitmapTest].
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class ColorExtractorTest {

    private fun bitmapOf(vararg argb: Int): Bitmap {
        val bmp = Bitmap.createBitmap(argb.size, 1, Bitmap.Config.ARGB_8888)
        argb.forEachIndexed { x, c -> bmp.setPixel(x, 0, c) }
        return bmp
    }

    @Test fun `a saturated cover yields that hue as the seed`() {
        val seed = ColorExtractor.seedOf(bitmapOf(0xFFD32F2F.toInt()))
        val expected = Hct.fromInt(0xFFD32F2F.toInt()).hue
        assertEquals(expected, seed.primary.hue, 12.0)   // tolerance: Palette quantizes
        assertTrue("a red cover is not monochrome", !seed.isMonochrome)
    }

    @Test fun `a greyscale cover is monochrome and carries no hue`() {
        val seed = ColorExtractor.seedOf(bitmapOf(0xFF202020.toInt(), 0xFF9E9E9E.toInt()))
        assertTrue("greyscale must not invent a hue", seed.isMonochrome)
        assertEquals(0.0, seed.primary.chroma, 0.0)
    }

    @Test fun `a greyscale cover is monochrome but still carries a mean for the backdrop ground`() {
        // Finding 14: seedOf's monochrome early return used to hand back the bare ArtSeed.NEUTRAL
        // — artMean == null — for ANY desaturated cover, not just a missing one. That made
        // backdropText solve against `bg` while ArtBackdrop kept drawing the real cover behind it,
        // silently reintroducing the pre-fix contrast failure for every black-and-white sleeve.
        // isMonochrome must stay true (a grey cover must not invent a hue) while artMean must be
        // non-null (the backdrop still draws this cover, so text still needs the real ground).
        val seed = ColorExtractor.seedOf(bitmapOf(0xFF202020.toInt(), 0xFF9E9E9E.toInt()))
        assertTrue("a real greyscale cover must still be monochrome", seed.isMonochrome)
        assertNotNull(
            "a real greyscale cover must still record a mean, or the backdrop solve silently " +
                "falls back to `ground = bg` while ArtBackdrop keeps drawing the cover",
            seed.artMean,
        )
    }

    @Test fun `a null bitmap is the neutral seed`() {
        assertEquals(ArtSeed.NEUTRAL, ColorExtractor.seedOf(null))
    }

    @Test fun `wave hues are distinct, not five copies of one colour`() {
        val seed = ColorExtractor.seedOf(
            bitmapOf(0xFFD32F2F.toInt(), 0xFF1976D2.toInt(), 0xFF388E3C.toInt())
        )
        val hues = (listOf(seed.primary) + seed.wave).map { it.hue }
        hues.forEachIndexed { i, h ->
            hues.drop(i + 1).forEach { other ->
                val sep = kotlin.math.abs(h - other).let { minOf(it, 360.0 - it) }
                assertTrue("hues $h and $other are only $sep apart", sep >= 15.0)
            }
        }
    }

    @Test fun `a bile cover is rotated away from the raw swatch`() {
        // Dark yellow-green, chosen (not just a plausible-looking hex) to land comfortably
        // inside the disliked band after Palette's quantization: the raw swatch here measures
        // hue 94.0 / chroma 31.3, well clear of both the 90/111 band edges and the chroma-16
        // floor, so this is testing the rotation itself rather than sitting on a boundary that
        // could pass by accident. Unrotated it themes the app the colour of bile, which is the
        // single ugliest failure this pipeline can produce. The disliked band (90.0..111.0) is
        // Material's `DislikeAnalyzer.isDisliked`'s, mirrored in ColorExtractor's own
        // escapeDislikedHue now that the tone-based fix (gone from this tree) can't apply here.
        val bile = 0xFF524700.toInt()
        val seed = ColorExtractor.seedOf(bitmapOf(bile))
        assertTrue(
            "the seed hue must not land in the disliked dark yellow-green band, was ${seed.primary.hue}",
            seed.primary.hue !in 90.0..111.0,
        )
    }

    @Test fun `a cover records its mean colour, and no artwork records none`() {
        val withArt = ColorExtractor.seedOf(bitmapOf(0xFFD32F2F.toInt()))
        assertNotNull("a real cover must record a mean for the backdrop solve", withArt.artMean)
        assertNull("no artwork must record no mean, so the ground stays bg", ColorExtractor.seedOf(null).artMean)
    }
}
