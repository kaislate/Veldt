// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Builds `/rest/<endpoint>` URLs.
 *
 * Uses OkHttp's [HttpUrl] builder rather than string concatenation because the query values
 * are user data — a password is never a value here, but a search term is, and a server name
 * can carry a subpath. `HttpUrl` also normalises the trailing-slash and default-port cases
 * that a self-hoster's typed URL produces.
 */
object SubsonicUrls {

    /**
     * A leading `scheme://`, if the string carries one.
     *
     * Matched as scheme-plus-authority rather than as a bare `scheme:` so that a typed
     * `myserver:4533` — a host and a port, which is exactly what a self-hoster types — is not
     * mistaken for a URL in some `myserver:` scheme.
     */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /**
     * A typed server address turned into a base URL, or null if it cannot be one.
     *
     * A bare `host:port` gains `http://` rather than `https://`. That is deliberate and it
     * matches the owner's decision: the overwhelmingly common self-hosted case is a LAN or
     * Tailscale address with no certificate, and defaulting to https there produces a TLS
     * error the user cannot act on. The UI warns about cleartext (Task 6); this function does
     * not silently upgrade or downgrade what was typed.
     */
    fun normalizeBase(raw: String): String? {
        // Whitespace comes off before the trailing slash, or "  http://h:4533/  " keeps its
        // slash. A foreign scheme is rejected here rather than left to the parser: prefixing
        // "http://" onto "ftp://h" yields "http://ftp://h", which OkHttp parses happily as
        // the host "ftp", so the check must happen before the prefix is attached.
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val scheme = SCHEME_PREFIX.find(trimmed)?.value
        val withScheme = when (scheme) {
            null -> "http://$trimmed"
            "http://", "https://" -> trimmed
            else -> return null
        }.trimEnd('/')
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.host.isEmpty()) return null
        return withScheme
    }

    /**
     * `<base>/rest/<endpoint>?v=&c=&f=json` plus [params].
     *
     * The three fixed parameters come first so the tests can assert a literal string; their
     * order is the only reason it is stable, and [SubsonicAuthTest] and [SubsonicUrlsTest]
     * both depend on it.
     */
    fun rest(baseUrl: String, endpoint: String, params: List<Pair<String, String>>): HttpUrl? {
        val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
        return base.newBuilder()
            .addPathSegment("rest")
            .addPathSegment(endpoint)
            .addQueryParameter("v", SubsonicAuth.API_VERSION)
            .addQueryParameter("c", SubsonicAuth.CLIENT_NAME)
            .addQueryParameter("f", "json")
            .apply { params.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
    }
}
