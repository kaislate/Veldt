// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/**
 * The only class in the app that performs Subsonic HTTP.
 *
 * [random] is injected so tests are deterministic; production supplies a `SecureRandom`.
 * Every call runs on [Dispatchers.IO] because OkHttp's synchronous `execute` blocks.
 */
@Singleton
class SubsonicClient @Inject constructor(
    private val http: OkHttpClient,
    private val random: Random,
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
            is SubsonicResult.Malformed -> ConnectionOutcome.Unreachable(result.reason)
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

    private suspend fun call(url: HttpUrl): SubsonicResult = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                // The envelope is authoritative, not the HTTP status: Subsonic servers answer
                // 200 with status="failed". A non-2xx with an unreadable body falls through to
                // Malformed by way of the parser, which is what we want.
                SubsonicEnvelope.parse(body)
            }
        } catch (e: IOException) {
            // The message may contain the host but never a credential — the url is not
            // interpolated here, and SubsonicAuth.redact is what any logging site must use.
            SubsonicResult.Malformed(e.message ?: "network error")
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
