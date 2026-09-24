// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import okhttp3.HttpUrl

/**
 * The authed `stream` url for one track (N2 Task 4).
 *
 * Built at LOAD time by the playback resolver and handed straight to the player's http layer —
 * it is never stored, logged or put in a `MediaItem`, because it carries `t=` and `s=`. Anything
 * that must print it goes through [SubsonicAuth.redact].
 *
 * Parameter order is `u, t, s, id`, then `maxBitRate` — pinned by `SubsonicStreamUrlsTest` as a
 * literal string. `maxBitRate` is sent only for a positive cap: null and 0 both mean "original",
 * and the owner's "original quality" setting must not reach the server as a parameter at all.
 */
object SubsonicStreamUrls {

    fun stream(
        creds: SubsonicCredentials,
        externalId: String,
        salt: String,
        maxBitRateKbps: Int?,
    ): HttpUrl? {
        val params = SubsonicAuth.tokenParams(creds.username, creds.password, salt) +
            ("id" to externalId) +
            listOfNotNull(maxBitRateKbps?.takeIf { it > 0 }?.let { "maxBitRate" to it.toString() })
        return SubsonicUrls.rest(creds.baseUrl, "stream", params)
    }
}
