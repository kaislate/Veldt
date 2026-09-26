// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.lyrics.LyricLine
import com.kaislate.veldtplayer.data.lyrics.Lyrics
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Random
import java.util.concurrent.TimeUnit

/** Plain JVM — no Robolectric. [SubsonicClient.lyrics] touches no Android type. */
class SubsonicClientLyricsTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    private val creds get() = SubsonicCredentials(server.baseUrl, "kyle", "hunter2")

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
    }

    @After fun tearDown() = server.close()

    @Test fun `the request calls getLyricsBySongId with id`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":false,"line":[{"value":"x"}]}]}}}""",
        )
        client.lyrics(creds, ServerCapabilities.BASELINE, "song-42")
        assertEquals("getLyricsBySongId", server.requests[0].target.substringAfterLast('/').substringBefore('?'))
        assertEquals("song-42", server.queryParam(0, "id"))
    }

    @Test fun `a synced entry maps start minus offset, clamped, into LyricLines`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":true,"offset":500,
                "line":[{"start":1000,"value":"la la"},{"start":2000,"value":"da da"}]}]}}}""",
        )
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(500, "la la"), LyricLine(1500, "da da"))),
            client.lyrics(creds, ServerCapabilities.BASELINE, "s1"),
        )
    }

    /** The clamp: an offset larger than a line's own start must not go negative. This is also
     *  this file's control — see the task report for dropping `.coerceAtLeast(0)`. */
    @Test fun `an offset larger than a line's start clamps that line to zero`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":true,"offset":500,"line":[{"start":200,"value":"x"}]}]}}}""",
        )
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(0, "x"))),
            client.lyrics(creds, ServerCapabilities.BASELINE, "s1"),
        )
    }

    @Test fun `a plain entry joins line values with newline`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":false,"line":[{"value":"line one"},{"value":"line two"}]}]}}}""",
        )
        assertEquals(
            Lyrics.Plain("line one\nline two"),
            client.lyrics(creds, ServerCapabilities.BASELINE, "s1"),
        )
    }

    @Test fun `an empty lyricsList is null`() = runTest {
        server.enqueue("""{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{}}}""")
        assertNull(client.lyrics(creds, ServerCapabilities.BASELINE, "s1"))
    }

    @Test fun `several entries prefer the synced one regardless of order`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":false,"line":[{"value":"plain text"}]},
                {"lang":"eng","synced":true,"line":[{"start":3000,"value":"synced text"}]}]}}}""",
        )
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(3000, "synced text"))),
            client.lyrics(creds, ServerCapabilities.BASELINE, "s1"),
        )
    }

    @Test fun `a code 40 error envelope is null`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        assertNull(client.lyrics(creds, ServerCapabilities.BASELINE, "s1"))
    }

    @Test fun `a dead host answers null rather than throwing`() = runTest {
        assertNull(client.lyrics(SubsonicCredentials("http://127.0.0.1:1", "kyle", "hunter2"), ServerCapabilities.BASELINE, "s1"))
    }
}
