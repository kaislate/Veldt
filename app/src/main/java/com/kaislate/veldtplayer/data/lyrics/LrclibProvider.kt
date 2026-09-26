// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * Lyrics from LRCLIB — the opt-in, off-by-default online source (spec §2.3, §5, §8).
 *
 * [enabled] is checked FIRST, before the cache or the network are touched at all: with the
 * setting off, this provider costs exactly zero — not even a cache read — so a track with a
 * cached [LrclibAnswer.Found] sitting on disk from a time the setting was on stays silent the
 * moment it's turned off (spec §5, "toggling LRCLIB off ... stops reading it"). That ordering is
 * the whole point of this class and is asserted directly by `LrclibProviderTest`, with a
 * negative control removing the check to show the test actually catches its absence.
 *
 * Otherwise: a cached [LrclibAnswer.Found] answers directly; a cached [LrclibAnswer.NotFound]
 * (not yet 7 days old — [LrclibCache] handles the expiry) answers null with no request at all;
 * anything else — no usable entry — makes exactly one request, caches whatever came back (never
 * a [LrclibAnswer.Failed], so a transient error is retried rather than remembered), and returns
 * accordingly.
 *
 * The cache key is `sourceId + '\u0000' + externalId` — the track's cross-source identity (spec
 * §5) — not anything derived from [Song.filePath] or [Song.uri]: no local path ever reaches this
 * class or the request it makes.
 */
class LrclibProvider(
    private val client: LrclibClient,
    private val cache: LrclibCache,
    private val enabled: suspend () -> Boolean,
) : LyricsProvider {

    override suspend fun lyricsFor(song: Song): Lyrics? {
        if (!enabled()) return null

        val key = song.sourceId + '\u0000' + song.externalId
        when (val cached = cache.read(key)) {
            is LrclibAnswer.Found -> return cached.lyrics
            LrclibAnswer.NotFound -> return null
            LrclibAnswer.Failed -> Unit // read() never returns this; exhaustive only
            null -> Unit // no usable entry — fall through to a request
        }

        val durationSec = Math.round(song.durationMs / 1_000.0)
        val answer = client.get(
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationSec = durationSec,
        )
        cache.write(key, answer)
        return (answer as? LrclibAnswer.Found)?.lyrics
    }
}
