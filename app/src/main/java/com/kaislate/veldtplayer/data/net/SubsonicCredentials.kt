// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

/**
 * What [SubsonicClient] needs to talk to one account's server.
 *
 * [toString] is overridden rather than left to the data class default because this value is
 * exactly the kind of thing that ends up interpolated into a log line or an exception message
 * by something that has never heard of [SubsonicAuth.redact] — a stray `"$creds"` in a debug
 * log must not be how a password leaves the device.
 */
data class SubsonicCredentials(val baseUrl: String, val username: String, val password: String) {
    override fun toString() = "SubsonicCredentials(baseUrl=$baseUrl, username=$username, password=<redacted>)"
}
