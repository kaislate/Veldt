// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.lyrics.LyricLine
import com.kaislate.veldtplayer.data.lyrics.Lyrics
import com.kaislate.veldtplayer.di.CryptoRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

/** What happened when we tried to talk to a server. */
sealed interface ConnectionOutcome {

    /** The server answered and accepted the credentials. */
    data class Reachable(
        val serverType: String?,
        val serverVersion: String?,
        val openSubsonic: Boolean,
        val capabilities: ServerCapabilities,
    ) : ConnectionOutcome

    /**
     * The server answered and refused. [error] classifies [code]; consult
     * [SubsonicError.meansCredentialsWontWork] rather than comparing to 40, because absent
     * credentials come back as 10.
     */
    data class Rejected(val error: SubsonicError, val code: Int, val message: String) : ConnectionOutcome

    /**
     * No usable answer: DNS, connection refused, timeout, TLS, a proxy's HTML error page.
     *
     * Deliberately distinct from [Rejected]. Telling a user to re-enter a correct password
     * because their Wi-Fi dropped is the single most annoying failure this screen can have.
     */
    data class Unreachable(val reason: String) : ConnectionOutcome
}

/** What happened when [SubsonicClient.scrobble] told a server about a play-through. */
sealed interface ScrobbleResult {

    /** The server accepted it: an empty ok envelope (design spec §2). */
    data object Ok : ScrobbleResult

    /** No usable answer: a dead socket, a timeout, an unparseable response. Queue it and retry. */
    data object Unreachable : ScrobbleResult

    /** The server answered and refused. [error] classifies [code] — see [SubsonicError
     * .meansCredentialsWontWork] for which codes mean "this account's credentials won't work". */
    data class Rejected(val error: SubsonicError, val code: Int) : ScrobbleResult
}

/**
 * The only class in the app that performs Subsonic HTTP.
 *
 * [random] is injected so tests are deterministic; production supplies a `SecureRandom`. The
 * [CryptoRandom] qualifier is what keeps it one — see that annotation for the failure it exists
 * to prevent. Every call runs on [Dispatchers.IO] because OkHttp's synchronous `execute` blocks.
 */
@Singleton
class SubsonicClient @Inject constructor(
    private val http: OkHttpClient,
    @param:CryptoRandom private val random: Random,
) {

    /**
     * Validate credentials, then describe the server.
     *
     * Capabilities are fetched only after `ping` succeeds. Not because the server requires it
     * — measured 2026-08-14, `getOpenSubsonicExtensions` needs no credentials — but because a
     * failed credential is the answer the user is waiting for, and a second request cannot
     * change it.
     */
    suspend fun probe(baseUrl: String, username: String, password: String): ConnectionOutcome {
        val salt = SubsonicAuth.newSalt(random)
        val url = SubsonicUrls.rest(baseUrl, "ping", SubsonicAuth.tokenParams(username, password, salt))
            ?: return ConnectionOutcome.Unreachable("that does not look like a server address")

        return when (val result = call(url)) {
            is SubsonicResult.Ok -> ConnectionOutcome.Reachable(
                serverType = result.body.stringOrNull("type"),
                serverVersion = result.body.stringOrNull("serverVersion"),
                openSubsonic = (result.body["openSubsonic"] as? JsonPrimitive)?.content == "true",
                capabilities = capabilities(baseUrl),
            )
            is SubsonicResult.Failed -> ConnectionOutcome.Rejected(result.error, result.code, result.message)
            // Through the seam, always. `reason` is not credential-bearing today, but this
            // string is rendered by the UI and may be logged by anything, and the class KDoc
            // used to assert that as a behavioural claim about text this class does not own.
            is SubsonicResult.Malformed -> ConnectionOutcome.Unreachable(SubsonicAuth.redact(result.reason))
        }
    }

    /**
     * The server's extension list, or [ServerCapabilities.BASELINE].
     *
     * Takes no credentials by design (§5.3, and measured). Every failure — 404, a Subsonic
     * error, an unreadable body, a dead socket — resolves to BASELINE, because "this server
     * has no extensions" is always a safe belief and an exception here would block adding an
     * account to an older server that works perfectly well.
     */
    suspend fun capabilities(baseUrl: String): ServerCapabilities {
        val url = SubsonicUrls.rest(baseUrl, "getOpenSubsonicExtensions", emptyList())
            ?: return ServerCapabilities.BASELINE
        val ok = call(url) as? SubsonicResult.Ok ?: return ServerCapabilities.BASELINE
        val list = ok.body["openSubsonicExtensions"] as? JsonArray ?: return ServerCapabilities.BASELINE

        val parsed = list.mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val name = entry.stringOrNull("name") ?: return@mapNotNull null
            val versions = (entry["versions"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                .orEmpty()
            name to versions
        }
        return if (parsed.isEmpty()) ServerCapabilities.BASELINE else ServerCapabilities(parsed.toMap())
    }

    private suspend fun call(url: HttpUrl): SubsonicResult = execute(Request.Builder().url(url).build())

    /**
     * `getLyricsBySongId` for [songId], mapped to [Lyrics], or null on ANY failure: a rejected
     * or unreachable server, an empty `lyricsList` (Navidrome's answer for "no lyrics" —
     * measured, spec §3), or an envelope this build cannot read. Never throws.
     *
     * A server may return several `structuredLyrics` entries (e.g. more than one language); the
     * first SYNCED entry wins, else the first entry at all (spec §4). A synced entry's line
     * times are `start - offset` — OpenSubsonic's own field, milliseconds, positive meaning the
     * lyric should appear SOONER — clamped so it never goes below zero, the same clamp
     * [com.kaislate.veldtplayer.data.lyrics.LrcParser] applies to an `.lrc` file's own
     * `[offset:]` tag. A plain entry's lines are joined with `\n`. Either shape resolving to
     * nothing — no lines, or blank text — is null, not an empty [Lyrics.Synced] or [Lyrics
     * .Plain].
     *
     * Built on [call], the same request path [fetchCatalog] uses, so credential placement stays
     * decided in exactly one place ([buildRequest]).
     */
    suspend fun lyrics(creds: SubsonicCredentials, caps: ServerCapabilities, songId: String): Lyrics? {
        val result = call(creds, "getLyricsBySongId", listOf("id" to songId), caps)
        val body = (result as? SubsonicResult.Ok)?.body ?: return null
        return parseStructuredLyrics(body)
    }

    private fun parseStructuredLyrics(body: JsonObject): Lyrics? {
        val lyricsList = body["lyricsList"] as? JsonObject ?: return null
        val entries = (lyricsList["structuredLyrics"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val chosen = entries.firstOrNull { it.booleanOrNull("synced") == true } ?: entries.first()
        val lines = (chosen["line"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        return if (chosen.booleanOrNull("synced") == true) {
            val offsetMs = chosen.longOrNull("offset") ?: 0L
            val lyricLines = lines.mapNotNull { line ->
                val start = line.longOrNull("start") ?: return@mapNotNull null
                LyricLine((start - offsetMs).coerceAtLeast(0), line.stringOrNull("value").orEmpty())
            }
            lyricLines.takeIf { it.isNotEmpty() }?.let { Lyrics.Synced(it.sortedBy { line -> line.timeMs }) }
        } else {
            val text = lines.mapNotNull { it.stringOrNull("value") }.joinToString("\n")
            text.takeIf { it.isNotBlank() }?.let { Lyrics.Plain(it) }
        }
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    /**
     * `getCoverArt` bytes for [id] at [sizePx], or null.
     *
     * Measured 2026-09-23 against Navidrome 0.64.0: `getCoverArt?id=<song id>&size=300` returns
     * the byte-identical `image/jpeg` as the song's own `coverArt` id and its parent album's
     * `al-…` id — so keying art by the song's [com.kaislate.veldtplayer.playback.TrackRef
     * .externalId] (spec §5.6) needs no extra id stored anywhere. An unknown id answers HTTP 200
     * with the usual JSON error envelope rather than a 4xx, which is why the Content-Type check
     * below — not the status alone — is what tells a real cover apart from that envelope.
     *
     * Shares [buildRequest] with [call] so the formPost-vs-GET choice and credential placement
     * are decided in exactly one place; this is the "future binary path" that KDoc already names.
     * Never throws and never puts [creds]`.baseUrl` (or anything else identifying the request)
     * in a message: every failure — missing credentials handled by the caller, a malformed base
     * url, a non-2xx, a non-image body, a dead socket — resolves to plain `null`.
     */
    suspend fun coverArt(
        creds: SubsonicCredentials,
        caps: ServerCapabilities,
        id: String,
        sizePx: Int,
    ): ByteArray? {
        val request = buildRequest(creds, "getCoverArt", listOf("id" to id, "size" to sizePx.toString()), caps)
            ?: return null
        return withContext(Dispatchers.IO) {
            try {
                http.newCall(request).execute().use { response ->
                    val body = response.body
                    // `type == "image"` rather than a raw header-string check: it tolerates a
                    // `; charset=` suffix and normalises case the way OkHttp's own MediaType does.
                    if (response.isSuccessful && body?.contentType()?.type == "image") body.bytes() else null
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * `scrobble` for one server track (network spec §7.4, design spec §2): [externalId] is the
     * song id, [submission] selects "now playing" (`false`) vs a "played" scrobble (`true`), and
     * [timeMs] — ms since epoch, sent verbatim as `time` — is the wall-clock moment the
     * play-through started, so a queued and later-delivered "played" scrobble still lands on
     * Navidrome with the ORIGINAL listening time rather than whenever the retry finally ran.
     * Omitted entirely (not sent as an empty parameter) when null, per the pinned API doc: `time`
     * is optional and a "now playing" call never carries one.
     *
     * Shares [call] with [fetchCatalog] and [lyrics], so credential placement (query vs. formPost
     * body) stays decided in the one place [buildRequest] already owns. Never throws: every
     * failure resolves to [ScrobbleResult.Unreachable] or [ScrobbleResult.Rejected].
     */
    suspend fun scrobble(
        creds: SubsonicCredentials,
        caps: ServerCapabilities,
        externalId: String,
        submission: Boolean,
        timeMs: Long?,
    ): ScrobbleResult {
        val params = buildList {
            add("id" to externalId)
            add("submission" to submission.toString())
            if (timeMs != null) add("time" to timeMs.toString())
        }
        return when (val result = call(creds, "scrobble", params, caps)) {
            is SubsonicResult.Ok -> ScrobbleResult.Ok
            is SubsonicResult.Failed -> ScrobbleResult.Rejected(result.error, result.code)
            is SubsonicResult.Malformed -> ScrobbleResult.Unreachable
        }
    }

    /**
     * `endpoint` with `params` (plus a fresh token+salt) using whatever transport [caps] allow:
     * a form-encoded POST when the server advertises `formPost`, else a GET with the credentials
     * in the query string. Used by [fetchCatalog]; `probe`/`capabilities` predate this and are
     * unaffected — they always GET, which is correct for them since neither takes a password
     * the server hasn't already been asked to validate over the (shorter-lived, unauthenticated)
     * query form.
     *
     * Internal rather than private: [fetchCatalog] is an extension function (so a future binary
     * endpoint — Task 6's `coverArt` — can be added the same way, without becoming a member of
     * this already-large class), and an extension function cannot see a `private` member.
     */
    internal suspend fun call(
        creds: SubsonicCredentials,
        endpoint: String,
        params: List<Pair<String, String>>,
        caps: ServerCapabilities,
    ): SubsonicResult {
        val request = buildRequest(creds, endpoint, params, caps)
            ?: return SubsonicResult.Malformed("that does not look like a server address")
        return execute(request)
    }

    /**
     * The one place that decides how a Subsonic request is transported. Returns null when
     * [SubsonicCredentials.baseUrl] cannot be parsed. Both the JSON [call] path above and a
     * future binary path (Task 6's cover art, which needs the raw response bytes rather than a
     * parsed envelope) build their request here, so credential placement — query vs. body — is
     * decided in exactly one place.
     */
    private fun buildRequest(
        creds: SubsonicCredentials,
        endpoint: String,
        params: List<Pair<String, String>>,
        caps: ServerCapabilities,
    ): Request? {
        val auth = SubsonicAuth.tokenParams(creds.username, creds.password, SubsonicAuth.newSalt(random))
        if (!caps.supports("formPost")) {
            val url = SubsonicUrls.rest(creds.baseUrl, endpoint, auth + params) ?: return null
            return Request.Builder().url(url).build()
        }
        val url = SubsonicUrls.rest(creds.baseUrl, endpoint, emptyList())
            ?.newBuilder()?.query(null)?.build()
            ?: return null
        val form = FormBody.Builder().apply {
            add("v", SubsonicAuth.API_VERSION)
            add("c", SubsonicAuth.CLIENT_NAME)
            add("f", "json")
            (auth + params).forEach { (k, v) -> add(k, v) }
        }.build()
        return Request.Builder().url(url).post(form).build()
    }

    private suspend fun execute(request: Request): SubsonicResult = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // The envelope is authoritative, not the HTTP status: Subsonic servers answer
                // 200 with status="failed". A non-2xx with an unreadable body falls through to
                // Malformed by way of the parser, which is what we want.
                SubsonicEnvelope.parse(body)
            }
        } catch (e: IOException) {
            // Through SubsonicAuth.redact, the mandatory seam for this layer. The previous
            // spelling asserted in a comment that OkHttp's exception text "may contain the host
            // but never a credential" — a claim about a string this class does not produce and
            // nothing verifies. An interceptor, a proxy library, or OkHttp itself may put the
            // full url in a message, and that url carries `t=`, `s=` and any userinfo.
            SubsonicResult.Malformed(SubsonicAuth.redact(e.message ?: "network error"))
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /** `size` for `getAlbumList2`: a page shorter than this ends the catalog. */
        const val PAGE_SIZE = 500

        /** How many `getAlbum` requests [fetchCatalog] runs at once. */
        const val ALBUM_CONCURRENCY = 4
    }
}

/**
 * Every song an account's server reports, across its whole `getAlbumList2` catalog.
 *
 * Pages `getAlbumList2` (`type=alphabeticalByName`, [SubsonicClient.PAGE_SIZE] per page) until
 * a page comes back shorter than a full page — a server never pads a short final page out to
 * the requested size, so a short page is unambiguously the last one. Every album id collected
 * is then fetched with `getAlbum`, [SubsonicClient.ALBUM_CONCURRENCY] at a time.
 *
 * The first non-OK result — at either stage — decides the whole outcome: there is no partial
 * catalog. A `getAlbum` that comes back credential-rejected mid-sync is exactly as fatal as one
 * that failed on the very first page, because by then this call has already spent the only
 * token it was given for this attempt.
 *
 * Songs are de-duplicated by [Song.externalId], keeping the first copy seen: Subsonic servers
 * may legitimately list one track under two albums (a compilation and its parent, a deluxe
 * reissue), and each such song must become one row, not two rows racing to own one unique key.
 */
suspend fun SubsonicClient.fetchCatalog(
    sourceId: String,
    creds: SubsonicCredentials,
    caps: ServerCapabilities,
): CatalogResult {
    val albumIds = mutableListOf<String>()
    var offset = 0
    while (true) {
        val page = call(
            creds,
            "getAlbumList2",
            listOf("type" to "alphabeticalByName", "size" to SubsonicClient.PAGE_SIZE.toString(), "offset" to offset.toString()),
            caps,
        )
        val ids = when (page) {
            is SubsonicResult.Ok -> SubsonicCatalogParser.albumIds(page.body)
            is SubsonicResult.Failed -> return CatalogResult.Rejected(page.error, page.code, page.message)
            is SubsonicResult.Malformed -> return CatalogResult.Unreachable(page.reason)
        }
        albumIds += ids
        if (ids.size < SubsonicClient.PAGE_SIZE) break
        offset += SubsonicClient.PAGE_SIZE
    }

    val semaphore = Semaphore(SubsonicClient.ALBUM_CONCURRENCY)
    val albumResults = coroutineScope {
        albumIds
            .map { id -> async { semaphore.withPermit { call(creds, "getAlbum", listOf("id" to id), caps) } } }
            .awaitAll()
    }

    val seen = mutableSetOf<String>()
    val songs = mutableListOf<Song>()
    for (result in albumResults) {
        when (result) {
            is SubsonicResult.Ok -> {
                for (song in SubsonicCatalogParser.songs(result.body, sourceId)) {
                    if (seen.add(song.externalId)) songs += song
                }
            }
            is SubsonicResult.Failed -> return CatalogResult.Rejected(result.error, result.code, result.message)
            is SubsonicResult.Malformed -> return CatalogResult.Unreachable(result.reason)
        }
    }
    return CatalogResult.Ok(songs)
}
