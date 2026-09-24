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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLDecoder
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Robolectric — [SubsonicCatalogParser.songs] builds a [com.kaislate.veldtplayer.playback
 * .VeldtUri], which uses `android.net.Uri`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicClientCatalogTest {

    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    private val creds get() = SubsonicCredentials(server.baseUrl, "kyle", "hunter2")
    private val formPost = ServerCapabilities(mapOf("formPost" to listOf(1)))

    private fun albumPage(vararg ids: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[${
            ids.joinToString(",") { """{"id":"$it"}""" }
        }]}}}"""

    private fun album(id: String, vararg songIds: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"$id","song":[${
            songIds.joinToString(",") { """{"id":"$it","title":"$it"}""" }
        }]}}}"""

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
    }

    @After fun tearDown() = server.close()

    @Test fun `pages until a short page, then fetches every album`() = runTest {
        // PAGE_SIZE is 500; a full page must trigger another request, a short one must stop.
        val first = (1..500).map { "a$it" }.toTypedArray()
        server.enqueue(albumPage(*first))
        server.enqueue(albumPage("a501"))
        (1..501).forEach { server.enqueue(album("a$it", "s$it")) }
        val result = client.fetchCatalog("acct", creds, ServerCapabilities.BASELINE) as CatalogResult.Ok
        assertEquals((1..501).map { "s$it" }.toSet(), result.songs.map { it.externalId }.toSet())
        assertEquals(
            listOf("0", "500"),
            server.requests.filter { "getAlbumList2" in it.target }
                .map { Regex("offset=(\\d+)").find(it.target)!!.groupValues[1] },
        )
    }

    @Test fun `with formPost the credentials travel in the body and not in the url`() = runTest {
        server.enqueue(albumPage())
        client.fetchCatalog("acct", creds, formPost)
        assertTrue("no request was made", server.requests.isNotEmpty())
        server.requests.forEach { req ->
            assertEquals("POST", req.method)
            val leakedInUrl = listOf("t=", "s=", "u=").filter { it in req.target }
            assertEquals("credentials leaked into the url: ${req.target}", emptyList<String>(), leakedInUrl)
            assertTrue("username missing from body: ${req.body}", "u=kyle" in req.body)
            assertTrue("token missing from body: ${req.body}", "t=" in req.body)
            assertTrue("salt missing from body: ${req.body}", "s=" in req.body)
        }
    }

    @Test fun `without formPost the request is a GET carrying the token`() = runTest {
        server.enqueue(albumPage())
        client.fetchCatalog("acct", creds, ServerCapabilities.BASELINE)
        assertTrue("no request was made", server.requests.isNotEmpty())
        server.requests.forEach { req ->
            assertEquals("GET", req.method)
            assertTrue("token missing from url: ${req.target}", "u=kyle&t=" in req.target)
        }
    }

    @Test fun `the token in the body is md5 of password plus that request's salt`() = runTest {
        server.enqueue(albumPage())
        client.fetchCatalog("acct", creds, formPost)
        val body = server.requests.single().body
        val params = body.split("&").associate { pair ->
            val (k, v) = pair.split("=", limit = 2)
            k to URLDecoder.decode(v, "UTF-8")
        }
        val salt = params.getValue("s")
        assertEquals(SubsonicAuth.md5Hex("hunter2" + salt), params.getValue("t"))
    }

    @Test fun `a code 40 on the album list is Rejected with the credentials error`() = runTest {
        server.enqueue(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":40,"message":"Wrong username or password"}}}""",
        )
        val result = client.fetchCatalog("acct", creds, ServerCapabilities.BASELINE)
        assertEquals(
            CatalogResult.Rejected(SubsonicError.of(40), 40, "Wrong username or password"),
            result,
        )
    }

    /** Review Focus 1. Control: temporarily make the implementation skip failed albums — must go red. */
    @Test fun `a failing album aborts the whole catalog`() = runTest {
        server.enqueue(albumPage("a1", "a2"))
        server.respond { recorded ->
            val id = Regex("id=([^&]+)").find(recorded.target)?.groupValues?.get(1)
                ?: Regex("id=([^&]+)").find(recorded.body)?.groupValues?.get(1)
            if (id == "a1") FakeHttpServer.Canned(500, "<html>server error</html>".toByteArray(), "text/html") else null
        }
        server.enqueue(album("a2", "s2"))
        val result = client.fetchCatalog("acct", creds, ServerCapabilities.BASELINE)
        assertTrue("expected Unreachable, got $result", result is CatalogResult.Unreachable)
    }

    @Test fun `a dead server is Unreachable and its reason carries no credential`() = runTest {
        val deadCreds = SubsonicCredentials("http://127.0.0.1:1", "kyle", "hunter2")
        val result = client.fetchCatalog("acct", deadCreds, ServerCapabilities.BASELINE)
        val reason = (result as? CatalogResult.Unreachable)?.reason
            ?: error("expected Unreachable, got $result")
        assertTrue("token leaked into reason: $reason", "t=" !in reason)
        assertTrue("password leaked into reason: $reason", "hunter2" !in reason)
    }
}
