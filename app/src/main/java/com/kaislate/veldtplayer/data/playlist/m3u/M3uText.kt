// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Turns the bytes of a `.m3u`/`.m3u8` file into the text [M3uParser] parses.
 *
 * Pure and framework-free — no Android import, no I/O — so the one step of an import that is pure
 * guesswork stays fully JVM-testable. [PlaylistImporter] supplies the bytes.
 *
 * ## Why this is guesswork at all
 *
 * The `.m3u` format has no encoding declaration. `.m3u8` is UTF-8 *by definition* and `.m3u` is
 * historically "the local 8-bit codepage", which in practice means ISO-8859-1 or something close
 * enough to it. Neither is announced in the file, so the encoding has to be inferred from the bytes,
 * and the inference must never be able to make things *worse* than not trying: the failure mode this
 * class exists to prevent is a track called `Björk.mp3` arriving as `Bj?rk.mp3` and matching nothing.
 *
 * ## The ladder
 *
 * 1. **A UTF-16 byte-order mark decides outright.** `FF FE` / `FE FF`. Not in the brief; here
 *    because `FE` and `FF` are not legal UTF-8 bytes *anywhere*, so without this rung a UTF-16
 *    playlist always falls through to Latin-1 and every path comes back interleaved with NUL —
 *    unreadable, unresolvable, and produced by default by Windows PowerShell 5.1 redirection.
 * 2. **Strict UTF-8** — `CodingErrorAction.REPORT`, so a single malformed sequence rejects the
 *    whole file rather than punching a `�` into one filename. Strictness is the whole
 *    detector: virtually no real Latin-1 text is accidentally valid UTF-8, because Latin-1 accented
 *    characters are lone high bytes and UTF-8 requires them to come in structured pairs.
 * 3. **ISO-8859-1**, which cannot fail — all 256 byte values map — so [decode] always returns.
 *
 * ## Every decision, and what it must NOT collapse
 *
 * | Decision | Why | Must not collapse | Guard |
 * |---|---|---|---|
 * | UTF-8 attempted before Latin-1 | `C3 A9` is "é" as UTF-8 and "Ã©" as Latin-1; both are legal readings and UTF-8 is overwhelmingly the likelier intent | a genuinely-UTF-8 file with a genuinely-Latin-1 one — Latin-1 first would decode *everything* and make the UTF-8 branch unreachable | strictness: the fallback runs only when UTF-8 is impossible |
 * | strict, not `REPLACE` | one bad byte must not be silently rewritten into `�`, which matches no file and cannot be undone | a decodable file with an undecodable one | `CodingErrorAction.REPORT` on both malformed input and unmappable characters |
 * | leading BOM stripped | a BOM is an encoding signal, not the first character of the first path | a U+FEFF that is genuinely part of a filename — legal on every filesystem Android runs on | fixed-width prefix removal, applied once, never a scan of the whole text |
 * | [isM3u8] does not branch | `.m3u8` is UTF-8 by definition, but a file that merely *claims* to be one is routinely Latin-1 | nothing — the collapse is deliberate: a mislabelled file must still import | pinned as a four-way equality, so a future branch on the flag shows up as a divergence |
 * | the *whole file* gets one charset | there is no per-line encoding signal to switch on | — | a file genuinely mixing UTF-8 and Latin-1 lines decodes entirely as Latin-1; see the class's note below |
 *
 * A file that mixes encodings — some tool appended Latin-1 lines to a UTF-8 playlist — decodes
 * wholly as Latin-1, so its UTF-8 lines come back mojibake-ed. That is not fixable from the bytes
 * (per-line sniffing would make two adjacent identical paths decode differently), and it is the
 * safe direction: mojibake paths fail to resolve and are persisted greyed under their imported
 * names, which is recoverable; a wrong match is not.
 */
object M3uText {

    /** U+FEFF. A byte-order mark when it leads the file; an ordinary character anywhere else. */
    private const val BOM = "﻿"

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * Decode a whole playlist file. Never throws, for any [bytes].
     *
     * [isM3u8] is the caller's reading of the file's extension. It is recorded rather than acted on
     * — see the class KDoc's table — and is kept in the signature because the *call site* is where
     * the distinction is knowable, and a future decision that does need it (say, refusing to write
     * a Latin-1 `.m3u8` back out) should not have to re-plumb it.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decode(bytes: ByteArray, isM3u8: Boolean): String {
        // 1. A UTF-16 BOM is the one case where the mark changes which charset can read the file at
        //    all, so it is consulted before anything is attempted. A UTF-16 decode can still fail
        //    (an odd-length body, an unpaired surrogate), and then the ordinary ladder runs.
        utf16CharsetOf(bytes)?.let { charset ->
            strictly(bytes, charset)?.let { return it.removePrefix(BOM) }
        }

        // 2. A UTF-8 BOM is dropped as bytes rather than as a character, so that the Latin-1
        //    fallback does not inherit it as the mojibake "ï»¿" glued to the first path.
        val body = if (bytes.startsWith(UTF8_BOM)) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes

        // 3. Strict UTF-8, then the charset that cannot fail. Exactly one BOM has been removed on
        //    every path through this function — never zero, never two.
        return strictly(body, Charsets.UTF_8) ?: String(body, Charsets.ISO_8859_1)
    }

    /**
     * [bytes] as [charset], or null when they are not valid [charset] at all.
     *
     * `REPORT` on both error kinds is what makes this a *detector* rather than a decoder: the
     * default action is `REPLACE`, which succeeds on every input and would make the return type a
     * lie and the fallback dead code.
     */
    private fun strictly(bytes: ByteArray, charset: Charset): String? =
        try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }

    /**
     * The UTF-16 flavour [bytes] announce, or null if they announce none.
     *
     * A UTF-32LE file also begins `FF FE` (followed by `00 00`) and is read here as UTF-16LE. Not
     * handled: no playlist writer emits UTF-32, and the misread is inert — the result fails to
     * match anything rather than matching the wrong thing.
     */
    private fun utf16CharsetOf(bytes: ByteArray): Charset? = when {
        bytes.startsWith(UTF16LE_BOM) -> Charsets.UTF_16LE
        bytes.startsWith(UTF16BE_BOM) -> Charsets.UTF_16BE
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
