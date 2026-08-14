// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.TimeUnit

/** Plain JVM — no Robolectric. The client touches no Android type. */
class SubsonicClientTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    // Field values measured against Navidrome 0.63.2 on 2026-08-14.
    private val okPing = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
        "serverVersion":"0.63.2 (be10f89c)","openSubsonic":true}}
    """.trimIndent()

    private val wrongPassword = """
        {"subsonic-response":{"status":"failed","version":"1.16.1",
        "error":{"code":40,"message":"Wrong username or password"}}}
    """.trimIndent()

    private val extensions = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonicExtensions":[
        {"name":"transcodeOffset","versions":[1]},{"name":"formPost","versions":[1]},
        {"name":"songLyrics","versions":[1,2]},{"name":"indexBasedQueue","versions":[1]},
        {"name":"transcoding","versions":[1]},{"name":"playbackReport","versions":[1]}]}}
    """.trimIndent()

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder()
                .callTimeout(10, TimeUnit.SECONDS)
                .build(),
            random = Random(42),
        )
    }

    @After fun tearDown() = server.close()

    @Test fun `a good credential reports the server it reached`() = runTest {
        server.enqueue(okPing)
        server.enqueue(extensions)
        val outcome = client.probe(server.baseUrl, "Kyle", "hunter2")
        val reachable = outcome as? ConnectionOutcome.Reachable ?: error("expected Reachable, got $outcome")
        assertEquals("navidrome", reachable.serverType)
        assertEquals("0.63.2 (be10f89c)", reachable.serverVersion)
        assertEquals(true, reachable.openSubsonic)
    }

    @Test fun `the ping request carries u, t and s and never the password`() = runTest {
        server.enqueue(okPing)
        server.enqueue(extensions)
        client.probe(server.baseUrl, "Kyle", "hunter2")
        assertEquals("Kyle", server.queryParam(0, "u"))
        val salt = server.queryParam(0, "s") ?: error("no salt sent")
        assertEquals(SubsonicAuth.md5Hex("hunter2" + salt), server.queryParam(0, "t"))
        val line = server.requestLines[0]
        assertTrue("the plaintext password went on the wire: $line", "hunter2" !in line)
    }

    @Test fun `wrong credentials are Rejected with the server's own code`() = runTest {
        server.enqueue(wrongPassword)
        val outcome = client.probe(server.baseUrl, "Kyle", "wrong")
        assertEquals(
            ConnectionOutcome.Rejected(SubsonicError.WRONG_CREDENTIALS, 40, "Wrong username or password"),
            outcome,
        )
    }

    @Test fun `a rejected probe does not go on to ask for capabilities`() = runTest {
        server.enqueue(wrongPassword)
        client.probe(server.baseUrl, "Kyle", "wrong")
        // Asserted as the list of paths requested, not as a count: a count of 1 would also
        // pass if the client skipped ping and asked only for extensions.
        assertEquals(
            listOf("ping"),
            server.requestLines.map { it.substringAfter("/rest/").substringBefore('?') },
        )
    }

    @Test fun `capabilities parses the six extensions Navidrome advertises`() = runTest {
        server.enqueue(extensions)
        val caps = client.capabilities(server.baseUrl)
        assertEquals(
            listOf("formPost", "indexBasedQueue", "playbackReport", "songLyrics", "transcodeOffset", "transcoding"),
            caps.extensions.keys.sorted(),
        )
        assertEquals(listOf(1, 2), caps.extensions["songLyrics"])
        assertEquals(true, caps.supports("songLyrics"))
        // Measured absent on Navidrome 0.63.2 — and the reason the password must be stored.
        assertEquals(false, caps.supports("apiKeyAuthentication"))
    }

    @Test fun `the capability request carries no credentials at all`() = runTest {
        server.enqueue(extensions)
        client.capabilities(server.baseUrl)
        // Measured: getOpenSubsonicExtensions succeeds unauthenticated on Navidrome 0.63.2,
        // which is what lets the account screen describe a server before a password exists.
        val sent = listOf("u", "t", "s", "p", "apiKey").filter { server.queryParam(0, it) != null }
        assertEquals("credentials were sent on an unauthenticated call", emptyList<String>(), sent)
    }

    @Test fun `a 404 on capabilities degrades to baseline rather than failing`() = runTest {
        server.enqueue("<html>not found</html>", status = 404, contentType = "text/html")
        assertEquals(ServerCapabilities.BASELINE, client.capabilities(server.baseUrl))
    }

    @Test fun `an unparseable body degrades to baseline`() = runTest {
        server.enqueue("{ this is not json")
        assertEquals(ServerCapabilities.BASELINE, client.capabilities(server.baseUrl))
    }

    @Test fun `a dead host is Unreachable, not Rejected`() = runTest {
        // Port 1 on loopback refuses instantly. The distinction matters: Unreachable must not
        // prompt the user to re-enter a password that is perfectly correct.
        val outcome = client.probe("http://127.0.0.1:1", "Kyle", "hunter2")
        assertTrue("expected Unreachable, got $outcome", outcome is ConnectionOutcome.Unreachable)
    }

    @Test fun `an unparseable base url is Unreachable without any request`() = runTest {
        val outcome = client.probe("ftp://nope", "Kyle", "hunter2")
        assertTrue("expected Unreachable, got $outcome", outcome is ConnectionOutcome.Unreachable)
        assertEquals(emptyList<String>(), server.requestLines.toList())
    }
}
