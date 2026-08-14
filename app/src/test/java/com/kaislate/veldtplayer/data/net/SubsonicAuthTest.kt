// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class SubsonicAuthTest {

    @Test fun `md5 matches the published vector`() {
        // RFC 1321 test vector. Pins the digest itself, so a future "optimisation" to a
        // different algorithm fails here rather than as a 40 from the server.
        assertEquals("900150983cd24fb0d6963f7d28e17f72", SubsonicAuth.md5Hex("abc"))
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", SubsonicAuth.md5Hex(""))
    }

    @Test fun `the token is md5 of password then salt, in that order`() {
        // Order matters and is not symmetric; a reversed concatenation would still be a
        // plausible-looking 32-char hex string that the server rejects with a 40.
        val expected = SubsonicAuth.md5Hex("hunter2" + "abc123")
        val params = SubsonicAuth.tokenParams("Kyle", "hunter2", "abc123").toMap()
        assertEquals(expected, params["t"])
        assertEquals("abc123", params["s"])
        assertEquals("Kyle", params["u"])
    }

    @Test fun `token params carry exactly u, t and s`() {
        assertEquals(
            listOf("u", "t", "s"),
            SubsonicAuth.tokenParams("Kyle", "hunter2", "abc123").map { it.first },
        )
    }

    @Test fun `the password itself never appears in the parameters`() {
        val password = "correct horse battery staple"
        val rendered = SubsonicAuth.tokenParams("Kyle", password, "abc123").joinToString()
        assertTrue("the plaintext password leaked into the query", password !in rendered)
    }

    @Test fun `salts differ between calls and are long enough`() {
        val a = SubsonicAuth.newSalt(Random(1))
        val b = SubsonicAuth.newSalt(Random(2))
        assertNotEquals(a, b)
        // The Subsonic spec requires at least six characters; this asserts the floor we chose.
        assertTrue("salt too short: $a", a.length >= 16)
        assertTrue("salt is not hex: $a", a.all { it in "0123456789abcdef" })
    }

    @Test fun `redaction removes EVERY credential parameter, not merely the first`() {
        val url = "http://h:4533/rest/ping?u=Kyle&t=deadbeef&s=abc123&p=plain&apiKey=k&v=1.16.1&f=json"
        val redacted = SubsonicAuth.redact(url)
        // Total over the set, not a sample: this is the exact shape of mutant that survives
        // an assertion which only checks that `t` is gone.
        val leaked = listOf("deadbeef", "abc123", "plain", "k=").filter { it in redacted }
        assertEquals("these credential values survived redaction", emptyList<String>(), leaked)
        // Non-credential parameters must survive, or a redacted url is useless for debugging.
        assertTrue("u= was redacted but is not a secret", "u=Kyle" in redacted)
        assertTrue("v= was lost", "v=1.16.1" in redacted)
        assertTrue("f= was lost", "f=json" in redacted)
    }

    @Test fun `redaction is order independent`() {
        val redacted = SubsonicAuth.redact("http://h/rest/ping?apiKey=zzz&s=yyy&t=xxx")
        val leaked = listOf("zzz", "yyy", "xxx").filter { it in redacted }
        assertEquals(emptyList<String>(), leaked)
    }
}
