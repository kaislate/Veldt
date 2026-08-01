// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parser behaviour, pinned input-by-input.
 *
 * A parser is nothing but normalisation decisions, so the tests that matter most here are the ones
 * asserting what the parser must *not* collapse: two paths differing only in surrounding
 * whitespace stay two paths, and a hyphenated artist keeps its hyphen. Those are the two places a
 * plausible "tidy it up" edit would silently merge distinct real-world inputs.
 *
 * Negative controls this file is designed to catch (see the task report):
 *  - requiring `#EXTM3U` reddens every headerless-playlist test;
 *  - splitting artist/title on the LAST separator reddens `a title containing a dash keeps it`;
 *  - splitting on a bare `-` instead of `" - "` reddens the hyphenated-artist test.
 */
class M3uParserTest {

    @Test fun `a bare playlist with no header still parses`() {
        assertEquals(
            listOf("a.mp3", "b.mp3"),
            M3uParser.parse("a.mp3\nb.mp3").map { it.path },
        )
    }

    @Test fun `EXTINF supplies duration, artist and title`() {
        val e = M3uParser.parse("#EXTM3U\n#EXTINF:210,Beck - Lost Cause\nx.mp3").single()
        assertEquals(210, e.durationSec)
        assertEquals("Beck", e.artist)
        assertEquals("Lost Cause", e.title)
        assertEquals("x.mp3", e.path)
    }

    @Test fun `EXTINF with no dash is a title with no artist`() {
        val e = M3uParser.parse("#EXTINF:90,Untitled\nx.mp3").single()
        assertEquals("Untitled", e.title)
        assertNull(e.artist)
    }

    @Test fun `a title containing a dash keeps the dash`() {
        val e = M3uParser.parse("#EXTINF:1,Alice - Bob - Carol\nx.mp3").single()
        assertEquals("Alice", e.artist)
        assertEquals("Bob - Carol", e.title)
    }

    // The separator is " - ", not "-", precisely so this does not become artist "Jay" / title
    // "Z - Big Pimpin'". Hyphenated artist names are common enough (Jay-Z, Blink-182, T-Pain) that
    // a bare-dash split is wrong on real libraries, not just in theory.
    @Test fun `a hyphenated artist name keeps its hyphen`() {
        val e = M3uParser.parse("#EXTINF:250,Jay-Z - Big Pimpin'\nx.mp3").single()
        assertEquals("Jay-Z", e.artist)
        assertEquals("Big Pimpin'", e.title)
    }

    @Test fun `blank lines and unknown directives are ignored`() {
        val out = M3uParser.parse("#EXTM3U\n\n#PLAYLIST:Mine\n\na.mp3\n\n")
        assertEquals(listOf("a.mp3"), out.map { it.path })
    }

    // VLC writes #EXTVLCOPT lines between the #EXTINF and the path it describes. Treating an
    // unknown directive as a reset would throw that #EXTINF away.
    @Test fun `an unknown directive between EXTINF and its path does not lose the header`() {
        val e = M3uParser.parse("#EXTINF:12,A - B\n#EXTVLCOPT:start-time=3\nx.mp3").single()
        assertEquals("x.mp3", e.path)
        assertEquals("A", e.artist)
        assertEquals("B", e.title)
        assertEquals(12, e.durationSec)
    }

    @Test fun `an EXTINF with no following path contributes nothing`() {
        assertEquals(emptyList<M3uEntry>(), M3uParser.parse("#EXTM3U\n#EXTINF:5,Orphan\n"))
    }

    @Test fun `carriage returns from a Windows-authored file are stripped`() {
        val e = M3uParser.parse("#EXTM3U\r\n#EXTINF:5,A - B\r\nx.mp3\r\n").single()
        assertEquals("x.mp3", e.path)
        assertEquals("B", e.title)
    }

    @Test fun `a malformed EXTINF duration degrades to null rather than throwing`() {
        val e = M3uParser.parse("#EXTINF:notanumber,A - B\nx.mp3").single()
        assertNull(e.durationSec)
        assertEquals("B", e.title)
    }

    // -1 is the M3U convention for "duration unknown"; a negative number of seconds is not a
    // duration, so it has to arrive as the same null an absent duration would.
    @Test fun `the unknown-duration sentinel arrives as null`() {
        assertNull(M3uParser.parse("#EXTINF:-1,A - B\nx.mp3").single().durationSec)
    }

    @Test fun `an EXTINF with no track info leaves artist and title null`() {
        val e = M3uParser.parse("#EXTINF:42,\nx.mp3").single()
        assertEquals(42, e.durationSec)
        assertNull(e.artist)
        assertNull(e.title)
    }

    // Paths are handed on byte-for-byte. A leading or trailing space is legal in a POSIX filename,
    // so trimming here would turn two distinct files into one — the exact collapse this parser
    // must not perform. Metadata is trimmed (see the next test); paths are not.
    @Test fun `whitespace around a path is part of the path`() {
        assertEquals(
            listOf("a.mp3 ", " a.mp3"),
            M3uParser.parse("a.mp3 \n a.mp3").map { it.path },
        )
    }

    // Metadata is a different case from a path: no real artist or title is distinguished by the
    // spaces around it, and "#EXTINF:210, Beck - Lost Cause" is ordinary in the wild.
    @Test fun `padding around EXTINF metadata is not part of the artist or title`() {
        val e = M3uParser.parse("#EXTINF: 210 , Beck - Lost Cause \nx.mp3").single()
        assertEquals(210, e.durationSec)
        assertEquals("Beck", e.artist)
        assertEquals("Lost Cause", e.title)
    }

    @Test fun `empty text parses to no entries`() {
        assertEquals(emptyList<M3uEntry>(), M3uParser.parse(""))
    }
}
