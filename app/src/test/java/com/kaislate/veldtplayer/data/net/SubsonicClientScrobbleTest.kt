// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import java.util.concurrent.TimeUnit

/** [SubsonicClient.scrobble] (network spec §7.4, design spec §2). Robolectric, matching
 *  [SubsonicClientCatalogTest]'s setup for this endpoint. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicClientScrobbleTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    private val creds get() = SubsonicCredentials(server.baseUrl, "kyle", "hunter2")
    private val formPost = ServerCapabilities(mapOf("formPost" to listOf(1)))

    /** `getAlbumList2` and `scrobble` share `/rest/<endpoint>`; the endpoint name is the last
     *  path segment, whether or not a query string follows it (a form POST strips the query). */
    private fun endpointOf(target: String): String = target.substringAfterLast('/').substringBefore('?')

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
    }

    @After fun tearDown() = server.close()

    private fun okEnvelope() = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

    @Test fun `a now-playing call sends id and submission=false with no time param, and returns Ok`() = runTest {
        server.enqueue(okEnvelope())
        val result = client.scrobble(creds, ServerCapabilities.BASELINE, "song-1", submission = false, timeMs = null)
        assertEquals("scrobble", endpointOf(server.requests.single().target))
        assertEquals("song-1", server.queryParam(0, "id"))
        assertEquals("false", server.queryParam(0, "submission"))
        assertNull("a now-playing call must omit time entirely, not send it empty", server.queryParam(0, "time"))
        assertEquals(ScrobbleResult.Ok, result)
    }

    @Test fun `a played call sends submission=true and time as the exact ms-since-epoch value`() = runTest {
        server.enqueue(okEnvelope())
        val result = client.scrobble(creds, ServerCapabilities.BASELINE, "song-2", submission = true, timeMs = 1_700_000_000_000L)
        assertEquals("song-2", server.queryParam(0, "id"))
        assertEquals("true", server.queryParam(0, "submission"))
        assertEquals("1700000000000", server.queryParam(0, "time"))
        assertEquals(ScrobbleResult.Ok, result)
    }

    @Test fun `with formPost caps the params travel in the POST body, not the query string`() = runTest {
        server.enqueue(okEnvelope())
        client.scrobble(creds, formPost, "song-3", submission = true, timeMs = 1_700_000_000_000L)
        val req = server.requests.single()
        assertEquals("POST", req.method)
        assertEquals("scrobble", endpointOf(req.target))
        val leakedInUrl = listOf("id=", "submission=", "time=").filter { it in req.target }
        assertEquals("params leaked into the url instead of the body: ${req.target}", emptyList<String>(), leakedInUrl)
        assertTrue("id missing from body: ${req.body}", "id=song-3" in req.body)
        assertTrue("submission missing from body: ${req.body}", "submission=true" in req.body)
        assertTrue("time missing from body: ${req.body}", "time=1700000000000" in req.body)
    }

    @Test fun `a code 40 error envelope is Rejected with the code`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        val result = client.scrobble(creds, ServerCapabilities.BASELINE, "song-4", submission = true, timeMs = 1L)
        assertEquals(ScrobbleResult.Rejected(SubsonicError.of(40), 40), result)
    }

    @Test fun `a dead socket is Unreachable`() = runTest {
        val deadCreds = SubsonicCredentials("http://127.0.0.1:1", "kyle", "hunter2")
        val result = client.scrobble(deadCreds, ServerCapabilities.BASELINE, "song-5", submission = false, timeMs = null)
        assertEquals(ScrobbleResult.Unreachable, result)
    }
}
