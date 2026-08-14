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
     */
    fun redact(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val head = url.substring(0, queryStart)
        val rebuilt = url.substring(queryStart + 1)
            .split('&')
            .joinToString("&") { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) return@joinToString pair
                val name = pair.substring(0, eq)
                if (name in SECRET_PARAMS) "$name=<redacted>" else pair
            }
        return "$head?$rebuilt"
    }
}
