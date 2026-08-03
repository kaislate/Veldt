// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The embedded rung's decode size.
 *
 * The rung around it needs a real music file with a real tag in it, which a unit test has no
 * way to produce; [AlbumArtFetcher.decodeEmbedded] is the part of it that takes picture bytes
 * and returns a bitmap, and the size of that bitmap is the whole property here.
 *
 * Robolectric because `BitmapFactory` is the thing under test: it is what reads the header in
 * the bounds pass and what honours `inSampleSize`. Real PNG bytes for the same reason — a stub
 * byte array has no dimensions to bound, and a decoder handed one reports -1 and gives up.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class AlbumArtFetcherTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val ceiling = AlbumArtFetcher.SESSION_MAX_PX

    private fun fetcher(maxPx: Int?) = AlbumArtFetcher(
        data = SongArt(
            songId = 1L,
            uri = "content://media/external/audio/media/1",
            filePath = "/storage/emulated/0/Music/1.mp3",
            hasEmbeddedArt = true,
        ),
        context = context,
        sample = ArtDecode.FULL,
        maxPx = maxPx,
    )

    /**
     * The session's bitmap is retained for the whole track by `CacheBitmapLoader` AND by
     * `MediaSessionBus.albumArt`, and walked pixel by pixel on every push. A 3000x2000 cover
     * decoded whole is ~24 MB of ARGB_8888 on a device with a ~192 MB heap cap.
     *
     * Asserted as a bound rather than an exact size, because the bound is the property: 1500
     * x1000 — what a "largest sample size that still exceeds the request" computation gives,
     * and the natural way to get this subtly wrong — would satisfy a comment saying "capped
     * at 1024" while still costing 6 MB.
     */
    @Test fun `an oversized embedded cover decodes within the session ceiling`() {
        val decoded = fetcher(maxPx = ceiling).decodeEmbedded(png(3000, 2000))!!

        assertTrue(
            "decoded ${decoded.width}x${decoded.height}; ceiling is ${ceiling}px",
            decoded.width <= ceiling && decoded.height <= ceiling,
        )
    }

    /**
     * The ceiling is a bound, not a resize. A cover already under it must arrive untouched,
     * or every ordinary file would reach the notification needlessly halved.
     */
    @Test fun `a cover already under the ceiling is decoded at its own size`() {
        val decoded = fetcher(maxPx = ceiling).decodeEmbedded(png(600, 400))!!

        assertEquals(listOf(600, 400), listOf(decoded.width, decoded.height))
    }

    /**
     * The UI path passes no ceiling and must keep every pixel: the now-playing art is drawn
     * full screen, and `PaletteCache` deliberately extracts colour from the natural-size
     * bitmap. Without this, capping the session path would quietly cap the whole app.
     */
    @Test fun `the uncapped path still decodes the cover at its natural size`() {
        val decoded = fetcher(maxPx = null).decodeEmbedded(png(3000, 2000))!!

        assertEquals(listOf(3000, 2000), listOf(decoded.width, decoded.height))
    }

    // ---------- a real image, without java.awt ----------

    /**
     * `javax.imageio` is not on the Android compile classpath, so the picture frame is built
     * here: an 8-bit greyscale PNG of solid black. Only the header matters — it is what the
     * bounds pass reads — but the pixels have to be genuinely decodable or `BitmapFactory`
     * reports no dimensions at all. Solid black deflates to a couple of KB even at 3000x2000.
     */
    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(),
            'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteArrayOutputStream().apply {
            writeBigEndian(width)
            writeBigEndian(height)
            write(8)    // bit depth
            write(0)    // colour type: greyscale
            write(0)    // deflate
            write(0)    // adaptive filtering
            write(0)    // no interlace
        }
        out.chunk("IHDR", header.toByteArray())
        // One filter byte (0 = none) in front of each row of pixels.
        out.chunk("IDAT", deflate(ByteArray(height * (width + 1))))
        out.chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeBigEndian(value: Int) {
        write(value ushr 24 and 0xFF)
        write(value ushr 16 and 0xFF)
        write(value ushr 8 and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.chunk(type: String, data: ByteArray) {
        val name = type.toByteArray(Charsets.US_ASCII)
        writeBigEndian(data.size)
        write(name)
        write(data)
        writeBigEndian(CRC32().apply { update(name); update(data) }.value.toInt())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
