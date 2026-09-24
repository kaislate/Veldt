// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.kaislate.veldtplayer.data.net.SubsonicEnvelope
import com.kaislate.veldtplayer.data.net.SubsonicResult

/**
 * Turns a Subsonic error envelope served as HTTP 200 into a typed player error (N2 Task 4).
 *
 * Measured against Navidrome 0.64.0: `stream` with a wrong or absent credential answers **200,
 * `application/json`**, `{"subsonic-response":{"status":"failed",…,"error":{"code":40|10}}}` — not
 * a 401. Nothing in the http layer objects to a 200, so unguarded the extractor is handed JSON as
 * audio, fails to sniff it, and the queue skips through every track on the account.
 *
 * Sits BELOW `ResolvingDataSource` (see [PlayerDataSources]) so it sees the resolved request, and
 * recognises one by [DataSpec.key], which `VeldtDataSpecResolver` sets to the logical `veldt://`
 * uri. Anything else — a local `content://` file, any other http load — is returned untouched
 * after a single string check.
 *
 * On an envelope it throws [DataSourceException] with a custom `reason`: ExoPlayer 1.8.0 copies
 * that into `PlaybackException.errorCode` (`handleIoException(e, e.reason)`, javap), which is the
 * only form that crosses the MediaSession boundary intact. The message is fixed text and never
 * the url, which carries `t=` and `s=` (Global Constraint 6).
 */
@UnstableApi
internal class SubsonicErrorGuard(private val upstream: DataSource) : DataSource by upstream {

    override fun open(dataSpec: DataSpec): Long {
        val length = upstream.open(dataSpec)
        // Only a request the resolver produced: its key is the logical veldt:// uri. A local
        // content:// or any other http load is never inspected.
        if (dataSpec.key?.startsWith("${VeldtUri.SCHEME}://") != true) return length
        val type = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
            ?.substringBefore(';')?.trim()?.lowercase() ?: return length
        if (type != "application/json" && type != "text/xml" && type != "application/xml") return length
        val body = readUpTo(MAX_ENVELOPE_BYTES)
        upstream.close()
        val result = SubsonicEnvelope.parse(body)
        val code = if (result is SubsonicResult.Failed && result.error.meansCredentialsWontWork) {
            ERROR_CODE_REMOTE_AUTH
        } else {
            ERROR_CODE_REMOTE_REFUSED
        }
        throw DataSourceException("server answered with an error envelope", code)
    }

    /**
     * javap, media3-datasource 1.8.0: `getResponseHeaders()` is a Java DEFAULT method returning an
     * empty map, while the other members `by upstream` forwards are abstract.
     *
     * **This override is load-bearing, not belt-and-braces — measured.** Kotlin's `by` delegation
     * does NOT forward a Java default method: with this line deleted, `SubsonicErrorGuardTest`'s
     * `the upstream's response headers are visible through the guard` goes red with
     * `expected:<[audio/flac]> but was:<null>` — the guard reports the interface's empty map, and
     * every consumer above it (`StatsDataSource` → `LoadEventInfo.responseHeaders`, and the ICY
     * header parse in `ProgressiveMediaPeriod`) would see no headers for any load.
     */
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    /** The body, up to [limit] bytes, as UTF-8. A longer body is truncated; it then fails to parse
     *  as an envelope and is refused, which is right for anything that large and not audio. */
    private fun readUpTo(limit: Int): String {
        val buf = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = upstream.read(buf, total, limit - total)
            if (n == C.RESULT_END_OF_INPUT) break
            total += n
        }
        return String(buf, 0, total, Charsets.UTF_8)
    }

    private companion object {
        const val MAX_ENVELOPE_BYTES = 64 * 1024
    }
}
