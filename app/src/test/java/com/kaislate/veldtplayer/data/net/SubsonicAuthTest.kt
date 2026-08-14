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

    @Test fun `successive calls on ONE generator differ — production has ONE SecureRandom`() {
        // The previous spelling here compared newSalt(Random(1)) with newSalt(Random(2)): two
        // different SEEDS, not two CALLS. Production injects a single SecureRandom via Hilt and
        // calls newSalt once per request, so the property that matters is that the SAME
        // generator yields a new salt every time. A memoizing or per-instance-caching newSalt
        // passed the old spelling while making every request reuse one salt — which makes the
        // t= token replayable.
        val r = Random(1)
        assertNotEquals(SubsonicAuth.newSalt(r), SubsonicAuth.newSalt(r))

        val generator = Random(1)
        val salts = List(16) { SubsonicAuth.newSalt(generator) }
        // WHICH ones collided, not how many: a count cannot say what went wrong.
        val repeated = salts.groupBy { it }.filterValues { it.size > 1 }.keys.sorted()
        assertEquals("these salts repeated across successive calls", emptyList<String>(), repeated)
    }

    @Test fun `a salt is long enough and is hex`() {
        val a = SubsonicAuth.newSalt(Random(1))
        // The Subsonic spec requires at least six characters; this asserts the floor we chose.
        assertTrue("salt too short: $a", a.length >= 16)
        assertTrue("salt is not hex: $a", a.all { it in "0123456789abcdef" })
    }

    @Test fun `redaction removes EVERY credential parameter, not merely the first`() {
        val url = "http://h:4533/rest/ping?u=Kyle&t=deadbeef&s=abc123&p=plain&apiKey=k&v=1.16.1&f=json"
        val redacted = SubsonicAuth.redact(url)
        // Total over the set, not a sample: this is the exact shape of mutant that survives
        // an assertion which only checks that `t` is gone.
        // Full name=value pairs, not bare values. "k=" was the original spelling here and it
        // was DEAD: the url contains "apiKey=k&", which yields the substrings "y=k" and "k&"
        // but never "k=", so the apiKey leg could not fail no matter what redact() did.
        val leaked = listOf("t=deadbeef", "s=abc123", "p=plain", "apiKey=k").filter { it in redacted }
        assertEquals("these credential values survived redaction", emptyList<String>(), leaked)
        // Non-credential parameters must survive, or a redacted url is useless for debugging.
        assertTrue("u= was redacted but is not a secret", "u=Kyle" in redacted)
        assertTrue("v= was lost", "v=1.16.1" in redacted)
        assertTrue("f= was lost", "f=json" in redacted)
    }

    @Test fun `redaction neutralises userinfo, which is not in the query string at all`() {
        // redact is the mandatory logging seam for the whole network layer, and it used to walk
        // only the query. An embedded password sits in the AUTHORITY, before the '?', so it
        // passed through untouched into any log line.
        val redacted = SubsonicAuth.redact("http://kyle:hunter2@h/rest/ping?f=json")
        val leaked = listOf("kyle", "hunter2").filter { it in redacted }
        assertEquals("userinfo survived the logging seam", emptyList<String>(), leaked)
        assertTrue("the url is no longer usable for debugging: $redacted", "h/rest/ping" in redacted)
        assertTrue("f= was lost", "f=json" in redacted)
    }

    @Test fun `userinfo is neutralised even when there is no query string to walk`() {
        // The early `if (queryStart < 0) return url` returned the url verbatim, so the whole
        // query-walking seam was skipped for exactly the urls that carry no parameters.
        val leaked = listOf("kyle", "hunter2")
            .filter { it in SubsonicAuth.redact("http://kyle:hunter2@h:4533") }
        assertEquals("userinfo survived on a url with no query", emptyList<String>(), leaked)
    }

    @Test fun `an at sign in a parameter value is not mistaken for userinfo`() {
        // The '@' must be looked for in the authority only, or a url ending in an email-shaped
        // search term gets its host chopped off in the logs.
        val redacted = SubsonicAuth.redact("http://h/rest/search3?f=json&query=kyle@example.com")
        assertTrue("the host was eaten: $redacted", redacted.startsWith("http://h/rest/search3"))
        assertTrue("a non-secret parameter was mangled: $redacted", "query=kyle@example.com" in redacted)
    }

    @Test fun `redaction is order independent`() {
        val redacted = SubsonicAuth.redact("http://h/rest/ping?apiKey=zzz&s=yyy&t=xxx")
        val leaked = listOf("zzz", "yyy", "xxx").filter { it in redacted }
        assertEquals(emptyList<String>(), leaked)
    }
}
