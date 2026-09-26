// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.net.FakeHttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * Plain JVM — no Robolectric; [LrclibClient] touches no Android type. Against
 * [FakeHttpServer], not the real `lrclib.net` — [baseUrl] is exactly the seam that exists for
 * this.
 */
class LrclibClientTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: LrclibClient

    private val userAgent = "Veldt/1.2.3 (https://github.com/kaislate/Veldt)"

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = LrclibClient(http = OkHttpClient(), baseUrl = server.baseUrl, userAgent = userAgent)
    }

    @After fun tearDown() = server.close()

    private fun param(name: String): String? =
        server.queryParam(0, name)?.let { URLDecoder.decode(it, "UTF-8") }

    @Test fun `a synced hit maps syncedLyrics through LrcParser`() = runTest {
        server.enqueue(
            """{"trackName":"t","artistName":"a","albumName":"al","duration":241.0,
                "instrumental":false,"plainLyrics":null,"syncedLyrics":"[00:01.00]hello"}""",
        )
        assertEquals(
            LrclibAnswer.Found(Lyrics.Synced(listOf(LyricLine(1000, "hello")))),
            client.get("t", "a", "al", 241L),
        )
    }

    @Test fun `a plain-only hit maps plainLyrics to Plain`() = runTest {
        server.enqueue(
            """{"trackName":"t","artistName":"a","albumName":"al","duration":241.0,
                "instrumental":false,"plainLyrics":"hello there","syncedLyrics":null}""",
        )
        assertEquals(
            LrclibAnswer.Found(Lyrics.Plain("hello there")),
            client.get("t", "a", "al", 241L),
        )
    }

    @Test fun `syncedLyrics wins over plainLyrics when both are present`() = runTest {
        server.enqueue(
            """{"instrumental":false,"plainLyrics":"unsynced fallback","syncedLyrics":"[00:02.00]synced wins"}""",
        )
        assertEquals(
            LrclibAnswer.Found(Lyrics.Synced(listOf(LyricLine(2000, "synced wins")))),
            client.get("t", "a", "al", 241L),
        )
    }

    /** [LrclibClient] checks `instrumental` before either lyrics field — see its KDoc. */
    @Test fun `instrumental true is NotFound even if a lyrics field is present`() = runTest {
        server.enqueue(
            """{"instrumental":true,"plainLyrics":"should be ignored","syncedLyrics":null}""",
        )
        assertEquals(LrclibAnswer.NotFound, client.get("t", "a", "al", 241L))
    }

    @Test fun `both lyrics fields blank is NotFound`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"","syncedLyrics":null}""")
        assertEquals(LrclibAnswer.NotFound, client.get("t", "a", "al", 241L))
    }

    @Test fun `a 404 is NotFound`() = runTest {
        server.enqueue("""{"code":404,"message":"Tracks not found"}""", status = 404)
        assertEquals(LrclibAnswer.NotFound, client.get("t", "a", "al", 241L))
    }

    @Test fun `a 500 is Failed, never NotFound`() = runTest {
        server.enqueue("""{"error":"boom"}""", status = 500)
        assertEquals(LrclibAnswer.Failed, client.get("t", "a", "al", 241L))
    }

    /** Negative control for the "network errors are Failed, not NotFound" claim: a connection
     *  that is refused outright (nothing listens on port 1) exercises the same catch block a
     *  real timeout would, without this test needing to wait 10 real seconds. */
    @Test fun `a dead host is Failed rather than throwing`() = runTest {
        val deadClient = LrclibClient(http = OkHttpClient(), baseUrl = "http://127.0.0.1:1", userAgent = userAgent)
        assertEquals(LrclibAnswer.Failed, deadClient.get("t", "a", "al", 241L))
    }

    @Test fun `an unparseable 200 body is Failed`() = runTest {
        server.enqueue("not json at all")
        assertEquals(LrclibAnswer.Failed, client.get("t", "a", "al", 241L))
    }

    @Test fun `the request carries exactly artist_name, track_name, album_name and duration`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"x","syncedLyrics":null}""")
        client.get(title = "Song Title", artist = "Some Artist", album = "An Album", durationSec = 241L)
        assertEquals("Some Artist", param("artist_name"))
        assertEquals("Song Title", param("track_name"))
        assertEquals("An Album", param("album_name"))
        assertEquals("241", param("duration"))
    }

    @Test fun `the request carries the configured User-Agent`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"x","syncedLyrics":null}""")
        client.get("t", "a", "al", 241L)
        assertEquals(userAgent, server.header(0, "User-Agent"))
    }
}
