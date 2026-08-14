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

    @Test fun `normalizeBase rejects what cannot be a server` () {
        val rejected = listOf("", "   ", "ftp://h", "http://", "not a url at all", "javascript:alert(1)")
        val accepted = rejected.filter { SubsonicUrls.normalizeBase(it) != null }
        assertEquals("these should have been rejected", emptyList<String>(), accepted)
    }

    @Test fun `rest returns null for a base url it cannot parse`() {
        assertNull(SubsonicUrls.rest("ftp://h", "ping", emptyList()))
    }
}
