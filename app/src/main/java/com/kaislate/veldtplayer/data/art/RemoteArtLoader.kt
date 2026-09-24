// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import android.graphics.Bitmap
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.playback.TrackRef
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads one track's cover art from its own server (spec §5.6), the rung [ArtSourcePlan] plans
 * for [ArtSource.Remote]. A `fun interface` rather than a direct dependency on [RemoteArtLoader]
 * so [AlbumArtFetcher] and `VeldtBitmapLoader` — both plain JVM-testable classes — can be handed
 * a fake instead of standing up [SubsonicClient] and [SubsonicSources].
 */
fun interface RemoteArt {
    /** [sizePx] is a pixel BUDGET, not a guarantee — see [RemoteArtLoader.load]. Never throws. */
    suspend fun load(ref: TrackRef, sizePx: Int): Bitmap?
}

/**
 * The production [RemoteArt]: `getCoverArt` over [SubsonicClient], keyed by [TrackRef
 * .externalId] (measured 2026-09-23 — see [SubsonicClient.coverArt]'s KDoc — so no second id
 * needs to be stored anywhere for this, Global Constraint 4).
 *
 * **Zero accounts, zero requests (spec §10).** [SubsonicSources.credentials] returning null —
 * the account was removed, or its secret cannot be read — is checked FIRST and short-circuits
 * before anything else runs, including [SubsonicSources.capabilities] and any network call.
 *
 * **Never throws.** A missing account, a JSON error envelope (an id the server does not
 * recognise), a non-2xx, a dead socket, and an undecodable image all resolve to plain `null` —
 * the same "degrade to the themed placeholder, never crash" contract [AlbumArtFetcher] already
 * holds its local rungs to.
 */
@Singleton
class RemoteArtLoader @Inject constructor(
    private val client: SubsonicClient,
    private val sources: SubsonicSources,
) : RemoteArt {

    override suspend fun load(ref: TrackRef, sizePx: Int): Bitmap? {
        val creds = sources.credentials(ref.sourceId) ?: return null
        val caps = sources.capabilities(ref.sourceId)
        val bytes = client.coverArt(creds, caps, ref.externalId, sizePx) ?: return null
        // sample = 1: the server was already asked for `sizePx` (§5.6's `size=` parameter), so
        // this decode only needs to enforce the same ceiling as a safety net against a server
        // that ignores the request and answers with the original, full-size file.
        return decodeCapped(bytes, sample = 1, maxPx = sizePx)
    }
}
