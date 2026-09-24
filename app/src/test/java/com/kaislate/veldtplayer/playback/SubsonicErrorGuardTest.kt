// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/**
 * Navidrome's HTTP-200 error envelope, turned into a typed player error (N2 Task 4, Step 4).
 *
 * Measured against Navidrome 0.64.0: `stream` with bad or absent credentials answers **200,
 * `application/json`**, body `{"subsonic-response":{"status":"failed",…"error":{"code":40|10}}}`.
 * Unguarded, the extractor is handed that JSON as audio and the queue skips through every track.
 *
 * A real [DefaultHttpDataSource] against [FakeHttpServer], because the guard's input is the http
 * layer's response headers — a fake `DataSource` would only restate what the test author believes
 * those headers look like. Robolectric for `android.net.Uri`.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicErrorGuardTest {

    private lateinit var server: FakeHttpServer

    @Before fun setUp() {
        server = FakeHttpServer().also { it.start() }
    }

    @After fun tearDown() = server.close()

    private val logical = VeldtUri.track("acct1", "s1")

    private fun envelope(code: Int) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"x"}}}"""

    /** A request as the resolver produces it: the real url, keyed by the logical uri. */
    private fun resolvedSpec(key: String? = logical) =
        DataSpec.Builder().setUri(Uri.parse("${server.baseUrl}/rest/stream?id=s1")).setKey(key).build()

    private fun guard() = SubsonicErrorGuard(DefaultHttpDataSource.Factory().createDataSource())

    private fun readAll(guard: SubsonicErrorGuard): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = guard.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun reasonFor(body: String, contentType: String = "application/json"): Int {
        server.enqueue(body, contentType = contentType)
        val g = guard()
        return assertThrows(DataSourceException::class.java) { g.open(resolvedSpec()) }.reason
    }

    @Test fun `the envelope codes map to the typed reasons`() {
        // TOTAL over the codes the spec names: 40 wrong password, 10 absent credentials (the
        // measured answer when `u` is missing), 41 token auth unsupported — all AUTH, since each
        // means "change this account's credentials" — and 70 not found, which is REFUSED.
        val codes = listOf(40, 10, 41, 70)
        assertEquals(
            listOf(
                40 to ERROR_CODE_REMOTE_AUTH,
                10 to ERROR_CODE_REMOTE_AUTH,
                41 to ERROR_CODE_REMOTE_AUTH,
                70 to ERROR_CODE_REMOTE_REFUSED,
            ),
            codes.map { it to reasonFor(envelope(it)) },
        )
    }

    @Test fun `a charset parameter on the content type is still caught`() {
        assertEquals(ERROR_CODE_REMOTE_AUTH, reasonFor(envelope(40), "application/json; charset=utf-8"))
    }

    @Test fun `a json body that is not an envelope is refused, not played`() {
        // A reverse proxy's JSON error page: not Subsonic, but certainly not audio.
        assertEquals(ERROR_CODE_REMOTE_REFUSED, reasonFor("""{"error":"bad gateway"}"""))
    }

    @Test fun `the exception message is fixed text and carries no url`() {
        // Global Constraint 6: the resolved url carries t= and s=; it must not ride out on a
        // message that ends up in a log or a bundle.
        server.enqueue(envelope(40))
        val g = guard()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("${server.baseUrl}/rest/stream?u=kyle&t=deadbeef&s=abc123&id=s1"))
            .setKey(logical)
            .build()
        val e = assertThrows(DataSourceException::class.java) { g.open(spec) }
        assertEquals("server answered with an error envelope", e.message)
    }

    @Test fun `an audio body opens normally and reads back its bytes`() {
        val audio = ByteArray(10_000) { (it % 251).toByte() }
        server.enqueueBytes(audio, contentType = "audio/flac")
        val g = guard()
        g.open(resolvedSpec())
        assertArrayEquals(audio, readAll(g))
        g.close()
    }

    @Test fun `a json body on a request the resolver did not produce is not inspected`() {
        // No veldt:// key: some other http load. The guard must stay out of it entirely.
        val body = envelope(40)
        server.enqueue(body)
        val g = guard()
        g.open(resolvedSpec(key = null))
        assertEquals(body, String(readAll(g), Charsets.UTF_8))
        g.close()
    }

    /**
     * `getResponseHeaders()` is a Java DEFAULT method on `DataSource` (javap, media3-datasource
     * 1.8.0). This pins that the guard reports the upstream's headers rather than the interface
     * default's empty map. See the KDoc on [SubsonicErrorGuard.getResponseHeaders] for what the
     * control run (override deleted) showed.
     */
    @Test fun `the upstream's response headers are visible through the guard`() {
        server.enqueueBytes(ByteArray(16), contentType = "audio/flac")
        val g = guard()
        g.open(resolvedSpec())
        assertEquals(listOf("audio/flac"), g.responseHeaders["Content-Type"])
        g.close()
    }
}
