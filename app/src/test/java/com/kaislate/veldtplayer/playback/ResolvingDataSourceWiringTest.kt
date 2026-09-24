// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import com.kaislate.veldtplayer.data.net.SubsonicCredentials
import com.kaislate.veldtplayer.data.net.SubsonicStreamUrls
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The layer that turns a logical uri into a real request at LOAD time: Media3's
 * `ResolvingDataSource.Resolver`, exercised directly rather than through an `ExoPlayer`.
 *
 * [VeldtDataSpecResolver] is a named class precisely so this test can call it. Driving it through a
 * player would test Media3's plumbing — which the report already establishes by disassembly — while
 * saying nothing about the one line of ours that decides whether a `content://` uri is touched.
 *
 * Robolectric because `DataSpec` is built on a real `android.net.Uri`, and because [VeldtUri]
 * percent-decodes through `Uri.decode`; the unit-test stub returns null for all of it.
 */
@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as VeldtArtUriTest does.
@Config(sdk = [34])
class ResolvingDataSourceWiringTest {

    private class FakeSource(
        override val sourceId: String,
        private val answer: String?,
    ) : RemoteUriResolver {
        override fun resolve(ref: TrackRef): String? = answer
    }

    private val local = "content://media/external/audio/media/122"
    private val logical = VeldtUri.track("acct1", "AL/42")
    private val real = "https://nav.example/rest/stream?id=AL%2F42"

    private fun subject(vararg sources: RemoteUriResolver) =
        VeldtDataSpecResolver(PlaybackUriResolver(sources.toSet()))

    @Test fun `a content uri DataSpec is handed back untouched`() {
        // Global constraint 5, at the layer that can break it. `assertSame`, not `assertEquals`:
        // DataSpec declares no `equals`, so a rebuilt copy would fail an equality check too — but
        // identity is the property that matters. A local file must reach DefaultDataSource as the
        // very object the media source built, not an equivalent one.
        val spec = DataSpec(Uri.parse(local))
        val returned = subject(FakeSource("acct1", "https://WRONG/")).resolveDataSpec(spec)
        assertSame(spec, returned)
    }

    @Test fun `a veldt uri DataSpec comes back carrying the resolved uri`() {
        val spec = DataSpec(Uri.parse(logical))
        val returned = subject(FakeSource("acct1", real)).resolveDataSpec(spec)
        assertEquals(real, returned.uri.toString())
    }

    @Test fun `a veldt uri no source claims is handed back untouched`() {
        // This slice's real configuration: the multibinding is empty until N2, so every load in
        // the shipped app takes this branch or the one above it.
        val spec = DataSpec(Uri.parse(logical))
        val returned = VeldtDataSpecResolver(PlaybackUriResolver(emptySet())).resolveDataSpec(spec)
        assertSame(spec, returned)
    }

    @Test fun `resolution rewrites the uri and carries the rest of the request across`() {
        // `buildUpon` copies field by field, so a future field added to DataSpec would be carried
        // silently — but position, length and headers are the ones a partially-buffered seek
        // depends on, and losing any of them would restart the request from zero.
        val spec = DataSpec.Builder()
            .setUri(logical)
            .setPosition(4096L)
            .setLength(65536L)
            .setHttpRequestHeaders(mapOf("Range" to "bytes=4096-"))
            .setFlags(DataSpec.FLAG_ALLOW_GZIP)
            .build()
        val returned = subject(FakeSource("acct1", real)).resolveDataSpec(spec)
        assertEquals(
            "resolution dropped part of the request",
            listOf<Any?>(real, 4096L, 65536L, mapOf("Range" to "bytes=4096-")),
            listOf<Any?>(
                returned.uri.toString(),
                returned.position,
                returned.length,
                returned.httpRequestHeaders,
            ),
        )
        assertEquals(DataSpec.FLAG_ALLOW_GZIP, returned.flags)
    }

    @Test fun `resolution pins the cache key to the logical uri`() {
        // Spec 4.4's third property. It is NOT `resolveReportedUri` that delivers it — see the
        // report: CacheKeyFactory.DEFAULT reads `DataSpec.key ?: DataSpec.uri`, and CacheDataSource
        // computes that from the DataSpec it is handed, never from DataSource.getUri(). So the key
        // is the lever, and setting it here makes the identity survive a rotated token whichever
        // side of this resolver a cache is later installed on.
        val spec = DataSpec(Uri.parse(logical))
        val returned = subject(FakeSource("acct1", real)).resolveDataSpec(spec)
        assertEquals(logical, returned.key)
    }

    @Test fun `a key chosen upstream is not overwritten`() {
        // An enclosing CacheDataSource stamps its own key onto the DataSpec before the upstream
        // sees it. Clobbering that would split one cached track across two entries.
        val spec = DataSpec.Builder().setUri(logical).setKey("chosen-upstream").build()
        val returned = subject(FakeSource("acct1", real)).resolveDataSpec(spec)
        assertEquals("chosen-upstream", returned.key)
    }

    // ---- The whole stack, through PlayerDataSources (N2 Task 4, carried gap 1) ----
    //
    // The tests above call VeldtDataSpecResolver directly, so none of them would notice if the
    // ResolvingDataSource layer were missing from the factory the player actually uses. These open
    // a veldt:// DataSpec through PlayerDataSources.dataSourceFactory — the same factory
    // PlaybackService hands ExoPlayer — against a real http server. Control run recorded in the
    // task report: with `ResolvingDataSource.Factory(guarded, …)` replaced by `guarded`, all three
    // open tests fail — DefaultDataSource passes the unknown `veldt` scheme to its http source,
    // which throws `MalformedURLException: unknown protocol: veldt`.

    private val salt = "abc123"
    private val token = "c402b3eac5900b52527b1f83f2fc94b3" // md5("hunter2" + "abc123")

    /** A static resolver for account "acct" that mints a real stream url against [server]. */
    private fun resolverFor(server: FakeHttpServer) = PlaybackUriResolver(
        setOf(
            object : RemoteUriResolver {
                override val sourceId = "acct"
                override fun resolve(ref: TrackRef): String? = SubsonicStreamUrls.stream(
                    SubsonicCredentials(server.baseUrl, "kyle", "hunter2"),
                    ref.externalId,
                    salt,
                    null,
                )?.toString()
            },
        ),
    )

    private fun withServer(block: (FakeHttpServer, DataSource) -> Unit) {
        FakeHttpServer().use { server ->
            server.start()
            val source = PlayerDataSources
                .dataSourceFactory(ApplicationProvider.getApplicationContext(), resolverFor(server))
                .createDataSource()
            block(server, source)
        }
    }

    private fun DataSource.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private val track = DataSpec(Uri.parse(VeldtUri.track("acct", "s1")))

    @Test fun `a veldt uri opened through the player's factory streams the server's audio`() = withServer { server, source ->
        val audio = ByteArray(8_192) { (it % 253).toByte() }
        server.enqueueBytes(audio, contentType = "audio/flac")
        source.open(track)
        val read = source.readAll()
        source.close()
        assertArrayEquals(audio, read)
        // And the request that reached the server was the resolved stream url for s1.
        assertEquals(
            "/rest/stream?v=1.16.1&c=veldt&f=json&u=kyle&t=$token&s=$salt&id=s1",
            server.requests.single().target,
        )
    }

    @Test fun `an error envelope through the player's factory is the typed auth error`() = withServer { server, source ->
        server.enqueue("""{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""")
        val e = assertThrows(DataSourceException::class.java) { source.open(track) }
        assertEquals(ERROR_CODE_REMOTE_AUTH, e.reason)
    }

    @Test fun `the uri reported after open is redacted`() = withServer { server, source ->
        // What DataSource.getUri() returns is what StatsDataSource copies into LoadEventInfo —
        // i.e. what every analytics listener and `EventLogger` sees. It must not carry t= or s=.
        server.enqueueBytes(ByteArray(16), contentType = "audio/flac")
        source.open(track)
        val reported = source.uri.toString()
        source.close()
        assertEquals(
            "the reported uri leaked a credential value, or was not the resolved request",
            listOf(true, true, false, false),
            listOf(
                "t=<redacted>" in reported,
                "s=<redacted>" in reported,
                token in reported,
                "s=$salt" in reported,
            ),
        )
    }

    @Test fun `a reported uri with nothing to redact is handed back as the same object`() {
        // Every content:// load passes through resolveReportedUri once opened; it must not cost a
        // re-parse, for the same reason the DataSpec passthrough above is an identity.
        val uri = Uri.parse(local)
        assertSame(uri, subject().resolveReportedUri(uri))
    }

    @Test fun `the load error policy never retries a remote error code and defers otherwise`() {
        // A wrong password does not get better with three retries; everything else keeps Media3's
        // default. TOTAL over the shapes the policy distinguishes: each custom code thrown bare,
        // the AUTH code wrapped as a cause, and two errors that must fall through to super.
        val policy = VeldtLoadErrorPolicy()
        fun delay(e: IOException) = policy.getRetryDelayMsFor(
            LoadErrorHandlingPolicy.LoadErrorInfo(
                LoadEventInfo(0L, DataSpec(Uri.parse("http://h/")), 0L),
                MediaLoadData(C.DATA_TYPE_MEDIA),
                e,
                /* errorCount = */ 2,
            ),
        )
        assertEquals(
            listOf(C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, 1_000L, 1_000L),
            listOf(
                delay(DataSourceException("x", ERROR_CODE_REMOTE_AUTH)),
                delay(DataSourceException("x", ERROR_CODE_REMOTE_REFUSED)),
                delay(IOException(DataSourceException("x", ERROR_CODE_REMOTE_AUTH))),
                delay(IOException("socket reset")),
                delay(DataSourceException("x", androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED)),
            ),
        )
    }
}
