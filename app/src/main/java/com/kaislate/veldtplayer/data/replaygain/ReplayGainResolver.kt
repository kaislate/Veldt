// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.replaygain

import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.net.ReplayGainLookup
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A song's ReplayGain values, resolved at play time (spec §5) and cached in memory for the life of
 * the process — nothing is stored, so there is no schema change.
 *
 * - **Local** tracks: their `REPLAYGAIN_*` tags, read with eAlvaTag ([LocalReplayGainReader]).
 * - **Server** tracks: the OpenSubsonic `replayGain` of `getSong` ([SubsonicClient.replayGain]),
 *   from the account's own server, about a track being played — the one kind of request the
 *   offline-by-default rule allows (spec §0).
 *
 * Definitive answers are cached, "none" included, so a server without ReplayGain costs one small
 * request per track per process, not one per play. A server that could not be reached is NOT
 * cached: the next play asks again. The cache is bounded ([CAPACITY], least recently used out) —
 * a listening session touches hundreds of tracks at most, and each entry is four floats.
 */
@Singleton
class ReplayGainResolver internal constructor(
    private val readLocal: (filePath: String?) -> ReplayGainValues?,
    private val readServer: suspend (TrackRef) -> ReplayGainLookup,
) {

    @Inject constructor(
        local: LocalReplayGainReader,
        client: SubsonicClient,
        sources: SubsonicSources,
    ) : this(
        readLocal = local::read,
        readServer = { ref ->
            val creds = sources.credentials(ref.sourceId)
            if (creds == null) {
                ReplayGainLookup.Unreachable
            } else {
                client.replayGain(creds, sources.capabilities(ref.sourceId), ref.externalId)
            }
        },
    )

    private val cache = object : LinkedHashMap<String, ReplayGainValues?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReplayGainValues?>?) =
            size > CAPACITY
    }

    suspend fun valuesFor(song: Song): ReplayGainValues? {
        val key = "${song.sourceId}:${song.externalId}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val server = VeldtUri.parse(song.uri)
        val values = if (server == null) {
            withContext(Dispatchers.IO) { readLocal(song.filePath) }
        } else {
            when (val lookup = readServer(server)) {
                is ReplayGainLookup.Answered -> lookup.values
                ReplayGainLookup.Unreachable -> return null
            }
        }
        synchronized(cache) { cache[key] = values }
        return values
    }

    private companion object {
        const val CAPACITY = 512
    }
}
