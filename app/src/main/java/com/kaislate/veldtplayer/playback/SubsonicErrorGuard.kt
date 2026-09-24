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
import java.io.IOException

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
 *
 * ### The positioned-open case (task 4+5 review, item 2)
 *
 * A seek, or this task's own resume-after-network-pause re-preparing at the saved position, opens
 * the request with `DataSpec.position > 0`. javap on `media3-datasource` 1.8.0's
 * `DefaultHttpDataSource.open`: on an HTTP 200 for a positioned request it calls `skipFully(position)`
 * to reach the requested byte — and a ~150-byte JSON error envelope runs out well before any
 * plausible seek position, so `skipFully` throws `HttpDataSourceException` with reason
 * `DataSourceException.POSITION_OUT_OF_RANGE` (2008) **from inside `upstream.open`, before this class
 * ever gets to read `Content-Type`**. Unhandled, 2008 maps to `ErrorAction.SKIP` (see
 * `PlayerErrorPolicy`), so a password rejection discovered by a seek — or by any Task 5 resume,
 * since re-preparing always re-opens at the saved position — skips instead of stopping.
 *
 * The fix does not trust "2008 at a nonzero position" as proof of an envelope: a real seek past a
 * genuinely short audio file must still surface as the ordinary out-of-range failure, unchanged. It
 * re-opens the SAME logical request at position 0 (length unset) purely to look — if THAT response
 * carries a json/xml envelope, it is parsed and the typed error thrown, exactly as the ordinary path
 * below does; otherwise (a real audio body, or the probe itself fails to open) the ORIGINAL exception
 * is rethrown unchanged. The probe reuses `upstream` rather than asking for a fresh `DataSource`: this
 * class holds a single `DataSource` instance, not the factory that built it, and `DataSource.open`
 * documents that a data source can be reopened at a new position after `close()` — which is, after
 * all, exactly what a seek within one track already relies on elsewhere in this stack.
 */
@UnstableApi
internal class SubsonicErrorGuard(private val upstream: DataSource) : DataSource by upstream {

    // DataSourceException.POSITION_OUT_OF_RANGE is deprecated (javap: java.lang.Deprecated, no
    // replacement of equal type on DataSourceException itself; PlaybackException.
    // ERROR_CODE_IO_POSITION_OUT_OF_RANGE is the same value but is a PlaybackException constant,
    // not reachable at this layer). It is still the exact, correct comparison for what
    // DefaultHttpDataSource actually throws here.
    @Suppress("DEPRECATION")
    override fun open(dataSpec: DataSpec): Long {
        val length = try {
            upstream.open(dataSpec)
        } catch (e: DataSourceException) {
            if (isVeldtKeyed(dataSpec) && dataSpec.position > 0L && e.reason == DataSourceException.POSITION_OUT_OF_RANGE) {
                probeForEnvelope(dataSpec)
            }
            // Either not this case, or the probe found no envelope (or couldn't even open): nothing
            // explains why the requested position was unreachable except that it genuinely was.
            throw e
        }
        if (isVeldtKeyed(dataSpec)) inspectEnvelope()
        return length
    }

    /** Only a request the resolver produced: its key is the logical veldt:// uri. A local
     *  content:// or any other http load is never inspected. */
    private fun isVeldtKeyed(dataSpec: DataSpec): Boolean =
        dataSpec.key?.startsWith("${VeldtUri.SCHEME}://") == true

    /**
     * Re-opens [dataSpec] at position 0 (length unset) on the SAME [upstream], purely to inspect the
     * response for an error envelope. Throws the typed [DataSourceException] if it finds one;
     * otherwise returns normally, leaving the caller to decide what to do (here: rethrow the
     * original position-out-of-range failure).
     */
    private fun probeForEnvelope(dataSpec: DataSpec) {
        val probeSpec = dataSpec.buildUpon().setPosition(0L).setLength(C.LENGTH_UNSET.toLong()).build()
        // The failed open does not guarantee upstream already released its connection; close
        // defensively before reusing the same instance for the probe.
        runCatching { upstream.close() }
        try {
            upstream.open(probeSpec)
        } catch (probeFailure: IOException) {
            // Couldn't even probe. Say nothing; the caller falls back to the original exception.
            return
        }
        inspectEnvelope()
        // inspectEnvelope only returns (rather than throwing) when the probe response was not an
        // envelope either — clean up its connection before the caller rethrows the original failure.
        upstream.close()
    }

    /**
     * Inspects the response [upstream] just opened for a Subsonic error envelope and throws the
     * typed [DataSourceException] if it finds one. Returns normally for anything else — a real audio
     * response, an unrelated content type, or no Content-Type header at all.
     */
    private fun inspectEnvelope() {
        val type = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }?.value?.firstOrNull()
            ?.substringBefore(';')?.trim()?.lowercase() ?: return
        if (type != "application/json" && type != "text/xml" && type != "application/xml") return
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
