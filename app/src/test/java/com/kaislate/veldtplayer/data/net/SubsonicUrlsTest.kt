// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubsonicUrlsTest {

    @Test fun `a rest url is asserted as a literal, not as a round trip`() {
        // Global Constraint 15. A round trip passes under an encoder and a decoder that are
        // wrong in matching ways; this project shipped exactly that bug once already.
        assertEquals(
            "http://192.168.50.111:4533/rest/ping?v=1.16.1&c=veldt&f=json",
            SubsonicUrls.rest("http://192.168.50.111:4533", "ping", emptyList()).toString(),
        )
    }

    @Test fun `caller params are appended after the fixed three`() {
        assertEquals(
            "http://h:4533/rest/getAlbum?v=1.16.1&c=veldt&f=json&id=abc",
            SubsonicUrls.rest("http://h:4533", "getAlbum", listOf("id" to "abc")).toString(),
        )
    }

    @Test fun `a base url with a trailing slash produces no double slash`() {
        assertEquals(
            "http://h:4533/rest/ping?v=1.16.1&c=veldt&f=json",
            SubsonicUrls.rest("http://h:4533/", "ping", emptyList()).toString(),
        )
    }

    @Test fun `a base url with a subpath keeps it — reverse proxies mount servers on one`() {
        assertEquals(
            "http://h/music/rest/ping?v=1.16.1&c=veldt&f=json",
            SubsonicUrls.rest("http://h/music", "ping", emptyList()).toString(),
        )
    }

    @Test fun `parameter values are percent-encoded`() {
        assertEquals(
            "http://h/rest/search3?v=1.16.1&c=veldt&f=json&query=a%20b%26c",
            SubsonicUrls.rest("http://h", "search3", listOf("query" to "a b&c")).toString(),
        )
    }

    @Test fun `normalizeBase accepts what a self-hoster actually types`() {
        assertEquals("http://192.168.50.111:4533", SubsonicUrls.normalizeBase("192.168.50.111:4533"))
        assertEquals("http://h:4533", SubsonicUrls.normalizeBase("  http://h:4533/  "))
        assertEquals("https://music.example.com", SubsonicUrls.normalizeBase("https://music.example.com"))
    }

    @Test fun `normalizeBase accepts a scheme in ANY case — soft keyboards capitalise`() {
        // RFC 3986 makes the scheme case-insensitive, and an Android soft keyboard
        // auto-capitalises the first character of a text field. Rejecting "Http://…" tells a
        // user with a perfectly good address that it "does not look like a server address".
        assertEquals("http://h:4533", SubsonicUrls.normalizeBase("Http://h:4533"))
        assertEquals("https://h", SubsonicUrls.normalizeBase("HTTPS://h"))
    }

    @Test fun `normalizeBase lowercases ONLY the scheme — a path is case-sensitive`() {
        // A reverse proxy commonly mounts a server on a capitalised subpath. Lowercasing the
        // whole string to fix the scheme would turn /Music into /music and 404 every request.
        assertEquals("http://h/Music", SubsonicUrls.normalizeBase("HtTp://h/Music"))
        assertEquals("http://h/Music", SubsonicUrls.normalizeBase("h/Music"))
    }

    @Test fun `normalizeBase strips userinfo so a password is never stored as a base url`() {
        val normalized = SubsonicUrls.normalizeBase("http://kyle:hunter2@h:4533")
        assertEquals("http://h:4533", normalized)
        // Asserted as absence too: the equality above is what breaks, but this names why.
        val leaked = listOf("kyle", "hunter2", "@").filter { it in normalized.orEmpty() }
        assertEquals("userinfo survived normalizeBase", emptyList<String>(), leaked)
        assertEquals("http://h/Music", SubsonicUrls.normalizeBase("http://kyle@h/Music"))
    }

    @Test fun `normalizeBase does NOT normalise a default port — the KDoc says so`() {
        // Pins the corrected class KDoc. If normalizeBase is ever rewritten to return
        // parsed.toString(), :80 disappears and this fails, forcing the doc to be re-read.
        assertEquals("http://h:80", SubsonicUrls.normalizeBase("http://h:80"))
        assertEquals("https://h:443", SubsonicUrls.normalizeBase("https://h:443"))
    }

    @Test fun `normalizeBase rejects what cannot be a server` () {
        val rejected = listOf("", "   ", "ftp://h", "http://", "not a url at all", "javascript:alert(1)")
        val accepted = rejected.filter { SubsonicUrls.normalizeBase(it) != null }
        assertEquals("these should have been rejected", emptyList<String>(), accepted)
    }

    @Test fun `rest returns null for a base url it cannot parse`() {
        assertNull(SubsonicUrls.rest("ftp://h", "ping", emptyList()))
    }
}
