// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Plain JVM — no Android type is touched.
 *
 * The fixture is a minimal, hand-built MP3 (spec §12): an ID3v2.3 tag with one `USLT` frame
 * (encoding 0 / ISO-8859-1, language `eng`, empty descriptor) followed by 20 silent MPEG-1
 * Layer III frames (`FF FB 90 64` + 413 zero bytes each — 128 kbps / 44100 Hz / no padding,
 * which is exactly `144 * 128000 / 44100 = 417` bytes per frame, header included). This is what
 * pins that eAlvaTag can actually read a real, if minimal, tagged audio file end to end — not
 * just a `Tag` object built by hand.
 *
 * Negative control this file is designed to catch (see the task report): making the provider
 * skip [LrcParser] and always return `Plain` reddens the timestamp test below.
 */
class EmbeddedLyricsProviderTest {

    private fun song(filePath: String?) = Song(
        id = 1L,
        sourceId = "local",
        externalId = "1",
        uri = "content://media/1",
        filePath = filePath,
        relativeKey = null,
        title = "t",
        artist = "a",
        album = "al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 0L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    // ---- fixture construction ------------------------------------------------------------------

    private fun syncSafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7F).toByte(),
        ((size shr 14) and 0x7F).toByte(),
        ((size shr 7) and 0x7F).toByte(),
        (size and 0x7F).toByte(),
    )

    private fun beInt32(size: Int): ByteArray = byteArrayOf(
        ((size shr 24) and 0xFF).toByte(),
        ((size shr 16) and 0xFF).toByte(),
        ((size shr 8) and 0xFF).toByte(),
        (size and 0xFF).toByte(),
    )

    /** One `USLT` frame: encoding 0 (ISO-8859-1), language `eng`, an empty descriptor, then [text]. */
    private fun usltFrame(text: String): ByteArray {
        val content = byteArrayOf(0x00) +
            "eng".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00) +
            text.toByteArray(Charsets.ISO_8859_1)
        return "USLT".toByteArray(Charsets.US_ASCII) + beInt32(content.size) + byteArrayOf(0x00, 0x00) + content
    }

    /** `0xFEFF` big-endian, ID3's own "UTF-16 encoded Unicode with BOM" marker. */
    private val utf16Bom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * One `USLT` frame: encoding 1 (UTF-16 with BOM), language `eng`, an empty descriptor, then
     * [text]. Each encoded-text field carries its OWN leading BOM (that is what "with BOM"
     * means per-field, not once for the whole frame) and is null-terminated with TWO zero bytes
     * — UTF-16's null character is two bytes, not one. The descriptor here is empty: just a BOM
     * immediately followed by that two-byte terminator. [text] is the frame's last field, so it
     * needs no terminator of its own — `Charsets.UTF_16` already emits a leading BOM for any
     * non-empty string, encoded big-endian (matching [utf16Bom]).
     */
    private fun usltFrameUtf16(text: String): ByteArray {
        val descriptor = utf16Bom + byteArrayOf(0x00, 0x00)
        val content = byteArrayOf(0x01) +
            "eng".toByteArray(Charsets.US_ASCII) +
            descriptor +
            text.toByteArray(Charsets.UTF_16)
        return "USLT".toByteArray(Charsets.US_ASCII) + beInt32(content.size) + byteArrayOf(0x00, 0x00) + content
    }

    /** `FF FB 90 64` + 413 zero bytes: MPEG-1 Layer III, 128 kbps, 44100 Hz, no padding, no CRC. */
    private val silentMpegFrame: ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(413)

    private fun audioFrames(count: Int = 20): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(count) { out.write(silentMpegFrame) }
        return out.toByteArray()
    }

    private fun tempMp3(bytes: ByteArray): File =
        File.createTempFile("fixture", ".mp3").apply { writeBytes(bytes) }

    private fun mp3WithLyrics(text: String): File {
        val frames = usltFrame(text)
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x03, 0x00, 0x00) + syncSafe(frames.size)
        return tempMp3(header + frames + audioFrames())
    }

    private fun mp3WithLyricsUtf16(text: String): File {
        val frames = usltFrameUtf16(text)
        val header = "ID3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x03, 0x00, 0x00) + syncSafe(frames.size)
        return tempMp3(header + frames + audioFrames())
    }

    /** No ID3 tag at all — just the audio frames. */
    private fun mp3WithoutLyrics(): File = tempMp3(audioFrames())

    /** Not a real MPEG stream at all — no frame sync anywhere in it. */
    private fun nonAudioFile(): File = tempMp3(ByteArray(200) { it.toByte() })

    // ---- tests ----------------------------------------------------------------------------------

    @Test fun `plain USLT text is Plain`() = runTest {
        val file = mp3WithLyrics("hello world")
        try {
            assertEquals(Lyrics.Plain("hello world"), EmbeddedLyricsProvider().lyricsFor(song(file.path)))
        } finally {
            file.delete()
        }
    }

    @Test fun `USLT text containing an LRC timestamp is Synced`() = runTest {
        val file = mp3WithLyrics("[00:01.00]x")
        try {
            assertEquals(
                Lyrics.Synced(listOf(LyricLine(1000, "x"))),
                EmbeddedLyricsProvider().lyricsFor(song(file.path)),
            )
        } finally {
            file.delete()
        }
    }

    /**
     * A real tagger writing non-Latin lyrics (Cyrillic here) would use UTF-16, not ISO-8859-1,
     * which cannot represent those characters at all. If eAlvaTag mis-decodes this fixture the
     * task report records the ACTUAL output as a finding rather than adjusting this expectation
     * to match wrong output.
     */
    @Test fun `UTF-16 USLT text decodes to the exact original characters`() = runTest {
        val text = "Привет [00:01.00]"
        val file = mp3WithLyricsUtf16(text)
        try {
            assertEquals(Lyrics.Plain(text), EmbeddedLyricsProvider().lyricsFor(song(file.path)))
        } finally {
            file.delete()
        }
    }

    @Test fun `a file with no lyrics frame is null`() = runTest {
        val file = mp3WithoutLyrics()
        try {
            assertNull(EmbeddedLyricsProvider().lyricsFor(song(file.path)))
        } finally {
            file.delete()
        }
    }

    @Test fun `a non-audio file is null`() = runTest {
        val file = nonAudioFile()
        try {
            assertNull(EmbeddedLyricsProvider().lyricsFor(song(file.path)))
        } finally {
            file.delete()
        }
    }

    @Test fun `a null filePath is null`() = runTest {
        assertNull(EmbeddedLyricsProvider().lyricsFor(song(null)))
    }
}
