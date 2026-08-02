// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pure JVM: [M3uText] has no Android imports, so neither does this.
 *
 * Several tests assert a **pair** of distinct inputs in one `assertEquals` rather than one input
 * each. That is deliberate and is this phase's house rule: the recurring defect here has been a
 * transformation that merges two genuinely different inputs, and a single-input test cannot see a
 * merge — only the pair can, because the failure message *is* the collapse.
 */
class M3uTextTest {

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    // ----------------------------------------------------------------- the brief's three

    @Test fun `utf8 content decodes to utf8`() {
        val bytes = "Björk.mp3".toByteArray(Charsets.UTF_8)
        assertEquals("Björk.mp3", M3uText.decode(bytes, isM3u8 = true))
    }

    @Test fun `latin1 content in a plain m3u does not become replacement characters`() {
        val bytes = "Björk.mp3".toByteArray(Charsets.ISO_8859_1)
        val out = M3uText.decode(bytes, isM3u8 = false)
        assertFalse(out.contains('�'))
        assertEquals("Björk.mp3", out)
    }

    @Test fun `a utf8 BOM is stripped rather than becoming part of the first path`() {
        val bytes = UTF8_BOM + "a.mp3".toByteArray()
        assertEquals("a.mp3", M3uText.decode(bytes, isM3u8 = true))
    }

    // ----------------------------------------------------------------- the folds, as pairs

    /**
     * The `.m3u8` extension is recorded at the call site and deliberately does **not** change the
     * decoding: `.m3u8` is UTF-8 by definition, but a file that merely *claims* to be one is
     * routinely Latin-1, and honouring the claim would make a mislabelled playlist import as
     * replacement characters. Four decodes of two byte strings, asserted together, so that a
     * branch on the flag shows up as a divergence rather than as a still-green single assertion.
     */
    @Test fun `the extension never changes the decoding, so a mislabelled file still imports`() {
        val latin1 = "Björk.mp3".toByteArray(Charsets.ISO_8859_1)
        val utf8 = "Björk.mp3".toByteArray(Charsets.UTF_8)
        assertEquals(
            listOf("Björk.mp3", "Björk.mp3", "Björk.mp3", "Björk.mp3"),
            listOf(
                M3uText.decode(latin1, isM3u8 = false),
                M3uText.decode(latin1, isM3u8 = true),
                M3uText.decode(utf8, isM3u8 = false),
                M3uText.decode(utf8, isM3u8 = true),
            ),
        )
    }

    /**
     * The ordering property, stated as the two files it must keep apart.
     *
     * `C3 A9` is a legal reading in *both* charsets — "é" as UTF-8, "Ã©" as ISO-8859-1 — so the
     * order of attempts decides, and it must be UTF-8 first. `F6` alone is Latin-1's "ö" and is not
     * valid UTF-8 at all, so it can only come back from the fallback. Latin-1-first would decode
     * every file successfully (all 256 bytes map) and the UTF-8 branch would become unreachable —
     * which is exactly the kind of change that leaves a single-input test green.
     */
    @Test fun `utf8 is tried first, so bytes legal in both charsets are read as utf8`() {
        val legalAsBoth = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        val latin1Only = byteArrayOf(0xF6.toByte())
        assertEquals(
            listOf("é", "ö"),
            listOf(
                M3uText.decode(legalAsBoth, isM3u8 = false),
                M3uText.decode(latin1Only, isM3u8 = false),
            ),
        )
    }

    /**
     * The BOM strip is a fixed-width prefix removal, not a loop and not a global scrub.
     *
     * U+FEFF is a legal (if perverse) character in a filename on every filesystem Android runs on,
     * so stripping every occurrence would rename a real file. Three inputs: the ordinary leading
     * BOM, a doubled one — of which exactly one is a byte-order mark and the second is content —
     * and one in the interior.
     */
    @Test fun `only the leading BOM is stripped, so a U+FEFF inside a path survives`() {
        assertEquals(
            listOf("a.mp3", "﻿a.mp3", "a﻿b.mp3"),
            listOf(
                M3uText.decode(UTF8_BOM + "a.mp3".toByteArray(), isM3u8 = true),
                M3uText.decode(UTF8_BOM + UTF8_BOM + "a.mp3".toByteArray(), isM3u8 = true),
                M3uText.decode("a﻿b.mp3".toByteArray(Charsets.UTF_8), isM3u8 = true),
            ),
        )
    }

    /**
     * UTF-16 with a byte-order mark, which is not in the brief and is here because of what happens
     * without it: `FF` and `FE` are not legal UTF-8 *anywhere*, so a UTF-16 playlist always falls
     * to the Latin-1 branch, and every path comes back interleaved with NUL — 47 unresolvable
     * entries named unreadably, from a file Windows PowerShell 5.1 produces by default from
     * `Get-Content x | ... > list.m3u`.
     *
     * Both endiannesses in one assertion: they are two distinct inputs and a sniff that handles one
     * is usually a sniff that has the other backwards.
     */
    @Test fun `a utf16 playlist with a BOM decodes to its paths, not to NUL-riddled latin1`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "Björk.mp3".toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "Björk.mp3".toByteArray(Charsets.UTF_16BE)
        assertEquals(
            listOf("Björk.mp3", "Björk.mp3"),
            listOf(M3uText.decode(le, isM3u8 = false), M3uText.decode(be, isM3u8 = false)),
        )
    }

    /**
     * Line structure is charset-independent, and the Latin-1 fallback must not disturb it: `\r` and
     * `\n` are the same bytes in every charset here, so a playlist with one high byte on one line
     * still hands [M3uParser] exactly the lines it had. Round-tripping the whole text — header,
     * CRLF terminators, trailing newline and all — pins that in one assertion.
     */
    @Test fun `the latin1 fallback preserves line structure and the ascii lines around it`() {
        val text = "#EXTM3U\r\n#EXTINF:210,Björk - Jóga\r\nBjörk.mp3\r\nplain.mp3\r\n"
        assertEquals(text, M3uText.decode(text.toByteArray(Charsets.ISO_8859_1), isM3u8 = false))
    }

    /**
     * A playlist is a file the user picked from a document provider; it can be anything at all,
     * including a truncated multi-byte sequence, a lone surrogate half, an odd-length UTF-16 body
     * or nothing. `decode` is the funnel every import goes through, so a throw here is a crash on
     * a user action, and it must not be reachable.
     */
    @Test fun `decode never throws, whatever the bytes are`() {
        val adversarial = listOf(
            "empty" to ByteArray(0),
            "lone FF" to byteArrayOf(0xFF.toByte()),
            "bare UTF-16LE BOM" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            "odd-length UTF-16LE body" to byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41),
            "truncated UTF-8 lead byte" to byteArrayOf(0xC3.toByte()),
            "UTF-8-encoded surrogate half" to
                byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "every byte value" to ByteArray(256) { it.toByte() },
        )
        for ((name, bytes) in adversarial) {
            assertNotNull(name, M3uText.decode(bytes, isM3u8 = false))
            assertNotNull(name, M3uText.decode(bytes, isM3u8 = true))
        }
    }
}
