// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri

/** A track identified by the source that owns it and that source's own id for it. */
data class TrackRef(val sourceId: String, val externalId: String)

/**
 * The logical playback URI (spec §4.4).
 *
 * Remote tracks enqueue `veldt://track/<sourceId>/<externalId>` and the service-side resolver turns
 * that into a real request at LOAD time. Three things fall out of that, and they are the reason
 * this indirection exists at all:
 *
 * 1. **Credentials never enter a `MediaItem`**, the session, the queue, or anything
 *    `dumpsys media_session` can print.
 * 2. **Bitrate and transcode parameters are late-bound**, so they are not frozen at enqueue time.
 * 3. **The cache identity is the logical id**, not a URL that changes when a token rotates.
 *
 * Both segments are percent-encoded. `externalId` is server-controlled and may legally contain
 * `/`, `%`, `?`, `#` and spaces; `sourceId` is registry-controlled today but encoding it too costs
 * nothing and keeps the mapping injective if that ever changes.
 */
object VeldtUri {

    const val SCHEME: String = "veldt"

    private const val TRACK_HOST = "track"
    private const val PREFIX = "$SCHEME://$TRACK_HOST/"

    /** The logical URI for one track. Both segments encoded; see the class KDoc. */
    fun track(sourceId: String, externalId: String): String =
        PREFIX + Uri.encode(sourceId) + "/" + Uri.encode(externalId)

    /**
     * The [TrackRef] this URI names, or null when it is not a well-formed `veldt://track/…`.
     *
     * Null is the passthrough signal: every non-`veldt` scheme — `content://` above all — must
     * reach the player untouched, so this returns null rather than throwing for anything it does
     * not own. Empty segments are rejected because an empty id is not a track, and letting one
     * through would produce a request for nothing.
     */
    fun parse(uri: String): TrackRef? {
        if (!uri.startsWith(PREFIX)) return null
        val rest = uri.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        val sourceId = Uri.decode(rest.substring(0, slash))
        val externalId = Uri.decode(rest.substring(slash + 1))
        if (sourceId.isEmpty() || externalId.isEmpty()) return null
        return TrackRef(sourceId, externalId)
    }
}
