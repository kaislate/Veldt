// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

/**
 * What a server said it can do, from `getOpenSubsonicExtensions`.
 *
 * [BASELINE] is the answer when the call 404s, errors, or returns something unreadable —
 * design spec §5.3. A server that does not answer is treated as plain Subsonic 1.16.1 with no
 * extensions, and every extension-gated feature must degrade to ABSENCE rather than to an
 * error. Measured on Navidrome 0.63.2: six extensions, and `apiKeyAuthentication` is not
 * among them.
 */
data class ServerCapabilities(val extensions: Map<String, List<Int>>) {

    fun supports(name: String): Boolean = extensions.containsKey(name)

    companion object {
        val BASELINE: ServerCapabilities = ServerCapabilities(emptyMap())
    }
}
