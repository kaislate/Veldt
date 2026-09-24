// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory

/**
 * The player's data-source stack, named in one place so the JVM suite can open a `veldt://` uri
 * through exactly what `PlaybackService` hands ExoPlayer (`ResolvingDataSourceWiringTest`).
 *
 * From the top: [ResolvingDataSource] turns the logical uri into the authed request (setting
 * `DataSpec.key` to the logical uri) → [SubsonicErrorGuard] inspects the response of a request
 * so keyed → [DefaultDataSource] picks `content://`, `file://`, … or http by scheme.
 *
 * **Order is the point.** The guard sits BELOW the resolver so it sees the resolved request and
 * its key; above it, it would see only `veldt://` specs that nothing below can open. Remove the
 * resolving layer and `DefaultDataSource` hands the unrecognised `veldt` scheme to its http source,
 * which fails with `MalformedURLException: unknown protocol: veldt` — surfacing as 2001
 * (NETWORK_CONNECTION_FAILED), i.e. a PAUSE that no retry can fix. That is the control run
 * recorded for `ResolvingDataSourceWiringTest`.
 *
 * `DefaultHttpDataSource` rather than OkHttp's data source: `media3-datasource-okhttp` is not in
 * the offline cache, and no new dependency is allowed. Timeouts match the app's OkHttp client
 * (10 s connect, 20 s read). For a `content://` load nothing changes but one extra wrapper whose
 * `open` returns after a single string check; `DefaultDataSource.Factory(context, http)` builds
 * the same local sources `DefaultDataSource.Factory(context)` does.
 */
@UnstableApi
object PlayerDataSources {

    fun dataSourceFactory(context: Context, uris: PlaybackUriResolver): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000)
            .setUserAgent("Veldt")
        val base = DefaultDataSource.Factory(context, http)
        val guarded = DataSource.Factory { SubsonicErrorGuard(base.createDataSource()) }
        return ResolvingDataSource.Factory(guarded, VeldtDataSpecResolver(uris))
    }

    fun mediaSourceFactory(context: Context, uris: PlaybackUriResolver): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory(context, uris), DefaultExtractorsFactory())
            .setLoadErrorHandlingPolicy(VeldtLoadErrorPolicy())
}

/**
 * Media3's default retry policy, except that a remote error envelope is never retried: a wrong
 * password does not get better with three more requests, and each retry is one more failed login
 * in the server's log. Everything else — including every local error — defers to `super`.
 *
 * The cause chain is walked because a loader can wrap the data source's exception.
 */
@UnstableApi
internal class VeldtLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (isRemoteEnvelopeError(loadErrorInfo.exception)) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    private fun isRemoteEnvelopeError(e: Throwable): Boolean =
        generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_DEPTH)
            .any {
                it is DataSourceException &&
                    (it.reason == ERROR_CODE_REMOTE_AUTH || it.reason == ERROR_CODE_REMOTE_REFUSED)
            }

    private companion object {
        /** Bounds the walk against a cyclic cause chain longer than one link. */
        const val MAX_CAUSE_DEPTH = 16
    }
}
