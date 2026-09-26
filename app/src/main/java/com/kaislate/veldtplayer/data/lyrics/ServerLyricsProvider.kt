// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.playback.VeldtUri
import javax.inject.Inject

/**
 * Lyrics from the account's own Subsonic/OpenSubsonic server, for a track that belongs to one.
 *
 * [VeldtUri.parse] returning null — a local track's `content://` uri — is "not this provider's
 * job", not a failure, so it costs nothing beyond the parse itself.
 *
 * The `songLyrics` capability is checked BEFORE anything that could reach the network:
 * [SubsonicSources.capabilities] is a cached, non-network read (spec §5.3), so a server that
 * never advertised the extension costs this provider exactly zero requests — the property
 * `ServerLyricsProviderTest` asserts directly on [com.kaislate.veldtplayer.data.net
 * .SubsonicClient]'s request log, not by inference from a null result.
 */
class ServerLyricsProvider @Inject constructor(
    private val client: SubsonicClient,
    private val sources: SubsonicSources,
) : LyricsProvider {

    override suspend fun lyricsFor(song: Song): Lyrics? {
        val ref = VeldtUri.parse(song.uri) ?: return null
        val caps = sources.capabilities(ref.sourceId)
        if (!caps.supports("songLyrics")) return null
        val creds = sources.credentials(ref.sourceId) ?: return null
        return client.lyrics(creds, caps, ref.externalId)
    }
}
