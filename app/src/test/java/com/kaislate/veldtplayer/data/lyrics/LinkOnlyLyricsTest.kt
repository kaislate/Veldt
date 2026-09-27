// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The total table for [LinkOnlyLyrics] (finding 20). Every row is judged and the mismatches are
 * reported together, so one run names every misjudged input rather than stopping at the first.
 */
class LinkOnlyLyricsTest {

    private val plainTable = listOf(
        // The exact body the owner's server returned for a whole album.
        "www.t.me/pmedia_music" to true,
        "  www.t.me/pmedia_music \n" to true,
        "t.me/pmedia_music" to true,
        "http://example.com" to true,
        "https://example.com/some/path?q=1#x" to true,
        "HTTPS://EXAMPLE.COM" to true,
        "www.example.com\nhttps://t.me/channel\n\nt.me/other" to true, // several link lines
        "www.example.com https://t.me/channel" to true, // two links on one line
        "[00:00.00]www.example.com" to true, // an LRC-timed watermark inside a Plain body
        "[00:00.00][00:05.00] https://t.me/x\n[00:10.00]www.example.com" to true,
        "" to true, // blank: no text at all
        "   \n\t\n" to true,
        // Kept: anything with real words in it, URL line included.
        "Real line one\nReal line two\nwww.t.me/pmedia_music" to false,
        "www.t.me/pmedia_music\nThe only real line" to false,
        "[00:01.00]Hello there\n[00:02.00]www.example.com" to false,
        "Telegram: t.me/pmedia_music" to false, // a word beside the link
        "one line" to false,
        "U.S.A." to false, // abbreviations are not hosts: single-letter labels, no TLD
        "a.m. p.m." to false,
        "[00:00.00]" to true, // only a timestamp, no text
    )

    @Test fun `plain text table`() {
        val wrong = plainTable.filter { (text, expected) -> LinkOnlyLyrics.isLinkOnly(text) != expected }
        assertEquals("misjudged: $wrong", emptyList<Pair<String, Boolean>>(), wrong)
    }

    @Test fun `a Plain result is judged by its text`() {
        assertEquals(true, LinkOnlyLyrics.isLinkOnly(Lyrics.Plain("www.t.me/pmedia_music")))
        assertEquals(false, LinkOnlyLyrics.isLinkOnly(Lyrics.Plain("a real line\nwww.t.me/pmedia_music")))
    }

    @Test fun `a Synced result is judged by its lines' text`() {
        val allLinks = Lyrics.Synced(listOf(LyricLine(0, "www.example.com"), LyricLine(5_000, "https://t.me/x")))
        val withGap = Lyrics.Synced(listOf(LyricLine(0, ""), LyricLine(5_000, "www.t.me/pmedia_music")))
        val real = Lyrics.Synced(listOf(LyricLine(0, "Sing it"), LyricLine(5_000, "www.t.me/pmedia_music")))
        assertEquals(true, LinkOnlyLyrics.isLinkOnly(allLinks))
        assertEquals(true, LinkOnlyLyrics.isLinkOnly(withGap))
        assertEquals(false, LinkOnlyLyrics.isLinkOnly(real))
    }
}
