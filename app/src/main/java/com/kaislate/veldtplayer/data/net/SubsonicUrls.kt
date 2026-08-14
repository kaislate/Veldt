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
 * can carry a subpath. [rest] therefore also absorbs the trailing slash a self-hoster's typed
 * URL carries. [normalizeBase] returns the typed string itself, edited only where it must be
 * — whitespace, a trailing slash, the case of the scheme, and any embedded userinfo — so it
 * does NOT normalise a default port: `http://h:80` comes back unchanged.
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
     *
     * Any `user:password@` is removed. A typed URL is stored as the account's base URL and is
     * handed to the logging seam, so accepting one verbatim would persist a password in the
     * account record and print it in every log line.
     */
    fun normalizeBase(raw: String): String? {
        // Whitespace comes off before the trailing slash, or "  http://h:4533/  " keeps its
        // slash. A foreign scheme is rejected here rather than left to the parser: prefixing
        // "http://" onto "ftp://h" yields "http://ftp://h", which OkHttp parses happily as
        // the host "ftp", so the check must happen before the prefix is attached.
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val matched = SCHEME_PREFIX.find(trimmed)?.value
        // Schemes are case-insensitive (RFC 3986) and Android soft keyboards capitalise the
        // first character of a text field, so "Http://h" is a valid address a real user types.
        // ONLY the matched prefix is lowercased: hosts are case-insensitive but paths are not,
        // and a reverse proxy commonly mounts a server at http://h/Music.
        val withScheme = when (val scheme = matched?.lowercase()) {
            null -> "http://$trimmed"
            "http://", "https://" -> scheme + trimmed.substring(matched.length)
            else -> return null
        }.trimEnd('/')
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.host.isEmpty()) return null
        // Rebuilt only in the userinfo case. Everywhere else the typed string is returned as
        // typed, which is what keeps a default port from being silently dropped.
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return parsed.newBuilder().username("").password("").build().toString().trimEnd('/')
        }
        return withScheme
    }

    /**
     * `<base>/rest/<endpoint>?v=&c=&f=json` plus [params].
     *
     * The three fixed parameters come first so the tests can assert a literal string; their
     * order is the only reason it is stable, and `SubsonicUrlsTest` depends on it.
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
