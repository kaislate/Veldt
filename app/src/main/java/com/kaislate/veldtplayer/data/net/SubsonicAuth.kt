// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import java.security.MessageDigest
import java.util.Random

/**
 * Subsonic wire authentication: token+salt, and the redaction that keeps it out of logs.
 *
 * **Token+salt is the only mode implemented, and that is a measurement rather than a
 * simplification.** Navidrome 0.63.2 does not advertise `apiKeyAuthentication` and does not
 * parse an `apiKey` parameter at all — a request carrying only `apiKey` comes back as code 10,
 * "missing parameter: 'u'", which is the answer a server gives when it has never heard of the
 * parameter. Since the token is recomputed from a fresh salt on every request, the plaintext
 * password must be recoverable at request time, which is what makes
 * [com.kaislate.veldtplayer.data.account.SecretBox] load-bearing rather than a nicety.
 *
 * MD5 is used because the API specifies it. It is not a security choice and must never be
 * reused for anything else in this codebase.
 */
object SubsonicAuth {

    /** The `c` parameter. Servers show it in their client lists. */
    const val CLIENT_NAME: String = "veldt"

    /** The `v` parameter. 1.16.1 is what Navidrome 0.63.2 reports supporting. */
    const val API_VERSION: String = "1.16.1"

    /** Parameters whose VALUES must never be logged, stored, or shown. */
    private val SECRET_PARAMS = setOf("t", "s", "p", "apiKey")

    private const val SALT_BYTES = 8

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    /**
     * A fresh salt. [random] is a parameter rather than a global so a test can be
     * deterministic; production passes `java.security.SecureRandom()`, which IS a `Random`.
     */
    fun newSalt(random: Random): String {
        val bytes = ByteArray(SALT_BYTES)
        random.nextBytes(bytes)
        return buildString(SALT_BYTES * 2) { bytes.forEach { append("%02x".format(it)) } }
    }

    /**
     * `u`, `t`, `s` — in that order, and never the password itself.
     *
     * The concatenation is password-then-salt. Reversed it still produces a well-formed
     * 32-character hex token that every server rejects with a 40, which is indistinguishable
     * from a typo at the UI, so the order is pinned by a test.
     */
    fun tokenParams(username: String, password: String, salt: String): List<Pair<String, String>> =
        listOf(
            "u" to username,
            "t" to md5Hex(password + salt),
            "s" to salt,
        )

    /**
     * The url with every credential value replaced.
     *
     * Written as a parse-and-rebuild over [SECRET_PARAMS] rather than as a regular expression
     * so that adding a parameter to that set is the whole change. A regex enumerates the names
     * a second time, and the two copies drift.
     *
     * Two things carry credentials, not one: the query parameters in [SECRET_PARAMS], and the
     * `user:password@` userinfo in the authority. [SubsonicUrls.normalizeBase] already strips
     * userinfo so it is never stored, but this is the mandatory logging seam for the whole
     * network layer and must hold for a url that reached it by any other path.
     */
    fun redact(url: String): String {
        val deauthed = redactUserInfo(url)
        val queryStart = deauthed.indexOf('?')
        if (queryStart < 0) return deauthed
        val head = deauthed.substring(0, queryStart)
        val rebuilt = deauthed.substring(queryStart + 1)
            .split('&')
            .joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) return@joinToString pair
                val name = pair.substring(0, eq)
                if (name in SECRET_PARAMS) "$name=<redacted>" else pair
            }
        return "$head?$rebuilt"
    }

    /**
     * The url with any `user:password@` replaced.
     *
     * The '@' is looked for inside the authority only — the span between `://` and the first
     * `/`, `?` or `#` — because an '@' is perfectly legal in a path or in a parameter value,
     * and cutting at the last one in the whole string would eat the host out of the log line
     * for a search whose term happens to be an email address.
     */
    private fun redactUserInfo(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val authorityStart = schemeEnd + "://".length
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) url.length else it }
        val at = url.substring(authorityStart, authorityEnd).lastIndexOf('@')
        if (at < 0) return url
        return url.substring(0, authorityStart) + "<redacted>@" + url.substring(authorityStart + at + 1)
    }
}
