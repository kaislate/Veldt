// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.replaygain.ReplayGainValues
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.TimeUnit

/** Plain JVM — no Robolectric. [SubsonicClient.replayGain] touches no Android type. */
class SubsonicClientReplayGainTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    private val creds get() = SubsonicCredentials(server.baseUrl, "kyle", "hunter2")
    private val formPost = ServerCapabilities(mapOf("formPost" to listOf(1)))

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
    }

    @After fun tearDown() = server.close()

    private fun song(replayGain: String?) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonic":true,
            "song":{"id":"tr-9","title":"Numb"${replayGain?.let { ""","replayGain":$it""" }.orEmpty()}}}}"""

    @Test fun `getSong is asked for the id, and its replayGain object is read`() = runTest {
        server.enqueue(song("""{"trackGain":-6.52,"albumGain":-7.1,"trackPeak":0.988547,"albumPeak":1.0,"baseGain":0}"""))
        val result = client.replayGain(creds, ServerCapabilities.BASELINE, "tr-9")
        assertEquals("getSong", server.requests[0].target.substringAfterLast('/').substringBefore('?'))
        assertEquals("tr-9", server.queryParam(0, "id"))
        assertEquals(ReplayGainLookup.Answered(ReplayGainValues(-6.52f, 0.988547f, -7.1f, 1.0f)), result)
    }

    @Test fun `with formPost the id travels in the POST body`() = runTest {
        server.enqueue(song("""{"trackGain":-3}"""))
        val result = client.replayGain(creds, formPost, "tr-9")
        val req = server.requests.single()
        assertEquals("POST", req.method)
        assertTrue("id missing from body: ${req.body}", "id=tr-9" in req.body)
        assertTrue("id leaked into the url: ${req.target}", "id=" !in req.target)
        assertEquals(ReplayGainLookup.Answered(ReplayGainValues(-3f, null, null, null)), result)
    }

    @Test fun `a song without replayGain is a definitive none`() = runTest {
        server.enqueue(song(null))
        assertEquals(ReplayGainLookup.Answered(null), client.replayGain(creds, ServerCapabilities.BASELINE, "tr-9"))
    }

    @Test fun `a replayGain with only peaks, or only strings, is none`() = runTest {
        server.enqueue(song("""{"trackPeak":0.9,"albumPeak":0.9}"""))
        assertEquals(ReplayGainLookup.Answered(null), client.replayGain(creds, ServerCapabilities.BASELINE, "a"))
        server.enqueue(song("""{"trackGain":"-6"}"""))
        assertEquals(ReplayGainLookup.Answered(null), client.replayGain(creds, ServerCapabilities.BASELINE, "b"))
    }

    @Test fun `an error envelope or a dead host is unreachable, never an exception`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":70,"message":"not found"}}}""",
        )
        assertEquals(ReplayGainLookup.Unreachable, client.replayGain(creds, ServerCapabilities.BASELINE, "gone"))
        val dead = SubsonicCredentials("http://127.0.0.1:1", "kyle", "hunter2")
        assertEquals(ReplayGainLookup.Unreachable, client.replayGain(dead, ServerCapabilities.BASELINE, "x"))
    }
}
