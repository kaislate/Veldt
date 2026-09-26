// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Plain JVM — no Robolectric. A real [LrclibClient] against [FakeHttpServer] and a real
 * [LrclibCache] against a real temp directory, not doubles for either: what this class exists to
 * prove is "the setting gate and the cache actually stop real HTTP requests from happening",
 * which a fake client/cache could only assert about itself, not about the network.
 *
 * Negative control this file is designed to catch (see the task report): moving the `enabled()`
 * check to AFTER `cache.read` in [LrclibProvider.lyricsFor] reddens `a disabled provider makes no
 * requests even with a cached Found on disk` below, because it would then return the cached
 * lyrics instead of null.
 */
class LrclibProviderTest {

    private lateinit var server: FakeHttpServer
    private lateinit var dir: File
    private lateinit var client: LrclibClient
    private lateinit var cache: LrclibCache

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        dir = Files.createTempDirectory("lrclib-provider-test").toFile()
        client = LrclibClient(http = OkHttpClient(), baseUrl = server.baseUrl, userAgent = "Veldt/test")
        cache = LrclibCache(dir) { 1_000_000L }
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun song(sourceId: String = "local", externalId: String = "song-1") = Song(
        id = 1L,
        sourceId = sourceId,
        externalId = externalId,
        uri = "content://media/1",
        filePath = "/should/never/be/sent.mp3",
        relativeKey = null,
        title = "Song Title",
        artist = "Some Artist",
        album = "An Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 241_000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private fun provider(enabled: Boolean) = LrclibProvider(client, cache, enabled = { enabled })

    @Test fun `a disabled provider makes no requests even with a cached Found on disk`() = runTest {
        val s = song()
        val key = s.sourceId + '\u0000' + s.externalId
        cache.write(key, LrclibAnswer.Found(Lyrics.Plain("cached lyrics")))

        val result = provider(enabled = false).lyricsFor(s)

        assertNull("disabled must answer null even though the cache has a Found entry", result)
        assertEquals(
            "disabled must make zero requests",
            emptyList<FakeHttpServer.Recorded>(),
            server.requests,
        )
    }

    @Test fun `an enabled provider with nothing cached makes exactly one request`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"la la","syncedLyrics":null}""")
        val result = provider(enabled = true).lyricsFor(song())
        assertEquals(Lyrics.Plain("la la"), result)
        assertEquals(1, server.requests.size)
    }

    @Test fun `a Found miss is cached, so a second lookup makes zero requests`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"la la","syncedLyrics":null}""")
        val s = song()
        val enabledProvider = provider(enabled = true)

        val first = enabledProvider.lyricsFor(s)
        val second = enabledProvider.lyricsFor(s)

        assertEquals(Lyrics.Plain("la la"), first)
        assertEquals(Lyrics.Plain("la la"), second)
        assertEquals("the second lookup must be answered from the cache", 1, server.requests.size)
    }

    @Test fun `a 404 NotFound is cached, so a second lookup makes zero requests and stays null`() = runTest {
        server.enqueue("""{"code":404,"message":"not found"}""", status = 404)
        val s = song()
        val enabledProvider = provider(enabled = true)

        val first = enabledProvider.lyricsFor(s)
        val second = enabledProvider.lyricsFor(s)

        assertNull(first)
        assertNull(second)
        assertEquals("the second lookup must be answered from the cache", 1, server.requests.size)
    }

    /** [LrclibCache.write] never persists [LrclibAnswer.Failed] (see its KDoc) — this is that
     *  decision observed from the provider's side: a transient failure gets retried, not
     *  remembered as a permanent miss. */
    @Test fun `a Failed answer is not cached, so the next lookup requests again`() = runTest {
        server.enqueue("""{"error":"boom"}""", status = 500)
        val s = song()
        val enabledProvider = provider(enabled = true)

        val first = enabledProvider.lyricsFor(s)
        assertNull(first)
        assertEquals(1, server.requests.size)

        server.enqueue("""{"instrumental":false,"plainLyrics":"recovered","syncedLyrics":null}""")
        val second = enabledProvider.lyricsFor(s)
        assertEquals(Lyrics.Plain("recovered"), second)
        assertEquals("a Failed answer must not have been cached", 2, server.requests.size)
    }

    @Test fun `the cache key pairs sourceId and externalId, not either alone`() = runTest {
        server.enqueue("""{"instrumental":false,"plainLyrics":"song A","syncedLyrics":null}""")
        server.enqueue("""{"instrumental":false,"plainLyrics":"song B","syncedLyrics":null}""")
        val enabledProvider = provider(enabled = true)

        val a = enabledProvider.lyricsFor(song(sourceId = "src1", externalId = "x"))
        val b = enabledProvider.lyricsFor(song(sourceId = "src2", externalId = "x"))

        assertEquals(Lyrics.Plain("song A"), a)
        assertEquals(Lyrics.Plain("song B"), b)
        assertEquals(2, server.requests.size)
    }
}
