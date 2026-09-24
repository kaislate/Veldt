// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `stream` url (N2 Task 4). Every assertion is a literal string, not a round trip — the same
 * rule `SubsonicUrlsTest` follows, for the same reason: an encoder and a decoder that are wrong in
 * matching ways pass a round trip.
 *
 * The token is `md5("hunter2" + "abc123")`, computed outside this code base (`printf
 * 'hunter2abc123' | md5sum`), so a password/salt swap inside [SubsonicAuth.tokenParams] could not
 * make the expectation agree with itself.
 */
class SubsonicStreamUrlsTest {

    private val creds = SubsonicCredentials("http://h:4533", "kyle", "hunter2")
    private val salt = "abc123"
    private val token = "c402b3eac5900b52527b1f83f2fc94b3"
    private val prefix = "http://h:4533/rest/stream?v=1.16.1&c=veldt&f=json&u=kyle&t=$token&s=$salt"

    @Test fun `without a cap the url carries no maxBitRate`() {
        assertEquals(
            "$prefix&id=s1",
            SubsonicStreamUrls.stream(creds, "s1", salt, maxBitRateKbps = null).toString(),
        )
    }

    @Test fun `a cap is appended after the id`() {
        assertEquals(
            "$prefix&id=s1&maxBitRate=192",
            SubsonicStreamUrls.stream(creds, "s1", salt, maxBitRateKbps = 192).toString(),
        )
    }

    @Test fun `a zero cap is omitted rather than sent`() {
        // In the Subsonic API maxBitRate=0 already means "no limit", but sending it is still a
        // different request: a server or a proxy that treats it otherwise would be handed a
        // parameter the owner's "original quality" never asked for.
        assertEquals(
            "$prefix&id=s1",
            SubsonicStreamUrls.stream(creds, "s1", salt, maxBitRateKbps = 0).toString(),
        )
    }

    @Test fun `an externalId with a slash and an ampersand is percent-encoded`() {
        // Server-controlled ids may contain both. Unencoded, `&c` would become a second parameter
        // and `/` would be read by a proxy as a path separator.
        assertEquals(
            "$prefix&id=a%2Fb%26c",
            SubsonicStreamUrls.stream(creds, "a/b&c", salt, maxBitRateKbps = null).toString(),
        )
    }
}
