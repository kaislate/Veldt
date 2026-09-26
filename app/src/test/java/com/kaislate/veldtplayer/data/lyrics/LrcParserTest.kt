// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One test per parsing rule in the lyrics design spec (§4), each pinning the exact [Lyrics]
 * value — not just a count or a `null`-check — so a rule that silently regresses (a wrong
 * fraction scale, a dropped offset clamp, a tag that leaks into the text) shows up as the right
 * value going wrong, not merely "a test failed somewhere".
 */
class LrcParserTest {

    // --- timestamp formats -------------------------------------------------------------------

    @Test fun `hundredths fraction`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(12340, "a"))),
            LrcParser.parse("[00:12.34]a"),
        )
    }

    @Test fun `milliseconds fraction`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(62345, "b"))),
            LrcParser.parse("[01:02.345]b"),
        )
    }

    @Test fun `no fraction at all`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(5000, "c"))),
            LrcParser.parse("[00:05]c"),
        )
    }

    @Test fun `minutes may exceed 59`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(4_500_000, "d"))),
            LrcParser.parse("[75:00.00]d"),
        )
    }

    // --- several timestamps on one line ------------------------------------------------------

    @Test fun `several leading timestamps on one line each emit their own line`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(10000, "chorus"), LyricLine(20000, "chorus"))),
            LrcParser.parse("[00:10.00][00:20.00]chorus"),
        )
    }

    // --- ordering ------------------------------------------------------------------------------

    @Test fun `lines out of order in the file are sorted by time`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(5000, "b"), LyricLine(10000, "a"))),
            LrcParser.parse("[00:10.00]a\n[00:05.00]b"),
        )
    }

    @Test fun `ties keep file order`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(10000, "first"), LyricLine(10000, "second"))),
            LrcParser.parse("[00:10.00]first\n[00:10.00]second"),
        )
    }

    // --- offset --------------------------------------------------------------------------------

    @Test fun `a positive offset shifts times earlier`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(9500, "x"))),
            LrcParser.parse("[offset:500]\n[00:10.00]x"),
        )
    }

    @Test fun `a negative offset shifts times later`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(10500, "x"))),
            LrcParser.parse("[offset:-500]\n[00:10.00]x"),
        )
    }

    @Test fun `an offset larger than the timestamp clamps to zero rather than going negative`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(0, "x"))),
            LrcParser.parse("[offset:20000]\n[00:10.00]x"),
        )
    }

    // --- metadata and comments dropped ----------------------------------------------------------

    @Test fun `metadata tags and a comment line are dropped, leaving the timed line`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(1000, "x"))),
            LrcParser.parse(
                "[ar:A]\n[ti:T]\n[al:B]\n[by:C]\n[length:03:00]\n[re:R]\n[ve:1]\n#comment\n[00:01.00]x",
            ),
        )
    }

    // --- the metadata set is the spec's enumerated tags, not any [letters:...] line -----------

    // Controller ruling: the SPEC's enumerated metadata set (§4) wins over a generic
    // [letters:...] pattern. "Chorus" is not ar/ti/al/by/length/re/ve/offset, so this is an
    // ordinary lyric annotation, not a tag, and must survive verbatim.
    //
    // Control (see the task report): reverting METADATA_LINE to the generic
    // `^\[[a-zA-Z]+:.*]$` pattern makes this test go red, because "Chorus" would then match
    // and the line would be dropped instead of kept.
    @Test fun `a non-enumerated bracket annotation survives as plain text`() {
        assertEquals(
            Lyrics.Plain("[Chorus: Poppy]"),
            LrcParser.parse("[Chorus: Poppy]"),
        )
    }

    @Test fun `an enumerated tag is dropped case-insensitively`() {
        assertEquals(
            Lyrics.Plain("some text"),
            LrcParser.parse("[AR:X]\nsome text"),
        )
    }

    @Test fun `a non-enumerated bracket annotation after a leading timestamp is that line's text`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(10000, "[Chorus: Poppy]"))),
            LrcParser.parse("[00:10.00][Chorus: Poppy]"),
        )
    }

    // --- enhanced (word-timed) tags stripped from text ------------------------------------------

    @Test fun `word-timing tags are stripped from the text`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(1000, "hello"))),
            LrcParser.parse("[00:01.00]<00:01.00>hel<00:01.50>lo"),
        )
    }

    // --- BOM and line endings -------------------------------------------------------------------

    @Test fun `a BOM prefix does not change the result`() {
        assertEquals(
            LrcParser.parse("[00:01.00]a\n[00:02.00]b"),
            LrcParser.parse("﻿[00:01.00]a\n[00:02.00]b"),
        )
    }

    @Test fun `CRLF line endings give the same result as LF`() {
        assertEquals(
            LrcParser.parse("[00:01.00]a\n[00:02.00]b"),
            LrcParser.parse("[00:01.00]a\r\n[00:02.00]b"),
        )
    }

    @Test fun `bare CR line endings give the same result as LF`() {
        assertEquals(
            LrcParser.parse("[00:01.00]a\n[00:02.00]b"),
            LrcParser.parse("[00:01.00]a\r[00:02.00]b"),
        )
    }

    // --- empty-text timed lines ------------------------------------------------------------------

    @Test fun `an empty-text timed line is kept as an instrumental gap`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(1000, "x"), LyricLine(3000, ""))),
            LrcParser.parse("[00:01.00]x\n[00:03.00]"),
        )
    }

    // --- no timed line at all: Plain or null ------------------------------------------------------

    @Test fun `no timed line at all is Plain text`() {
        assertEquals(
            Lyrics.Plain("just words\nmore"),
            LrcParser.parse("just words\nmore"),
        )
    }

    @Test fun `metadata is removed even from an untimed file, leaving Plain text`() {
        assertEquals(
            Lyrics.Plain("just words"),
            LrcParser.parse("[ar:A]\njust words"),
        )
    }

    @Test fun `only metadata is null`() {
        assertNull(LrcParser.parse("[ar:A]\n[ti:T]"))
    }

    @Test fun `only blank text is null`() {
        assertNull(LrcParser.parse("\n\n   \n"))
    }

    @Test fun `only timestamps with empty text is null`() {
        assertNull(LrcParser.parse("[00:01.00]\n[00:02.00]"))
    }

    // Controller ruling: a file with at least one timed line is treated as a synced file, so any
    // untimed plain-text line inside it is ignored rather than folded into the output — this is
    // true whether or not the timed lines carry text, which is the case this test pins.
    @Test fun `an untimed line inside a file that has timed lines is ignored, even when every timed line is empty`() {
        assertNull(LrcParser.parse("[00:01.00]\nsome untimed text\n[00:02.00]"))
    }

    @Test fun `an untimed line inside a file that has timed lines with text is ignored`() {
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(1000, "a"), LyricLine(2000, "b"))),
            LrcParser.parse("[00:01.00]a\nsome untimed text\n[00:02.00]b"),
        )
    }

    // --- malformed tag ------------------------------------------------------------------------

    @Test fun `a malformed time tag is treated as non-timed text`() {
        assertEquals(
            Lyrics.Plain("[aa:bb.cc]x"),
            LrcParser.parse("[aa:bb.cc]x"),
        )
    }
}
