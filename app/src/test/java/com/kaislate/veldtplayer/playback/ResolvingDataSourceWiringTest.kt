// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
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
    private val logical = VeldtUri.track("subsonic:acct1", "AL/42")
    private val real = "https://nav.example/rest/stream?id=AL%2F42"

    private fun subject(vararg sources: RemoteUriResolver) =
        VeldtDataSpecResolver(PlaybackUriResolver(sources.toSet()))

    @Test fun `a content uri DataSpec is handed back untouched`() {
        // Global constraint 5, at the layer that can break it. `assertSame`, not `assertEquals`:
        // DataSpec declares no `equals`, so a rebuilt copy would fail an equality check too — but
        // identity is the property that matters. A local file must reach DefaultDataSource as the
        // very object the media source built, not an equivalent one.
        val spec = DataSpec(Uri.parse(local))
        val returned = subject(FakeSource("subsonic:acct1", "https://WRONG/")).resolveDataSpec(spec)
        assertSame(spec, returned)
    }

    @Test fun `a veldt uri DataSpec comes back carrying the resolved uri`() {
        val spec = DataSpec(Uri.parse(logical))
        val returned = subject(FakeSource("subsonic:acct1", real)).resolveDataSpec(spec)
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
        val returned = subject(FakeSource("subsonic:acct1", real)).resolveDataSpec(spec)
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
        val returned = subject(FakeSource("subsonic:acct1", real)).resolveDataSpec(spec)
        assertEquals(logical, returned.key)
    }

    @Test fun `a key chosen upstream is not overwritten`() {
        // An enclosing CacheDataSource stamps its own key onto the DataSpec before the upstream
        // sees it. Clobbering that would split one cached track across two entries.
        val spec = DataSpec.Builder().setUri(logical).setKey("chosen-upstream").build()
        val returned = subject(FakeSource("subsonic:acct1", real)).resolveDataSpec(spec)
        assertEquals("chosen-upstream", returned.key)
    }
}
