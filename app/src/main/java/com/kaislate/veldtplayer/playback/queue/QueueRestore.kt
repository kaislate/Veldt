// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.SessionMediaId
import com.kaislate.veldtplayer.playback.TrackRef

/**
 * A [SavedItem] for [song], with exactly the fields `sessionMediaItem` would put on its
 * `MediaItem` — same mediaId, same logical uri, same DisplayNames-cleaned captions, same art —
 * so a queue saved from a live player and a queue rebuilt from the library are indistinguishable.
 */
internal fun savedItemOf(song: Song, playableUri: String) = SavedItem(
    mediaId = SessionMediaId.of(song),
    uri = playableUri,
    title = song.displayTitle(),
    artist = song.displayArtist(),
    album = song.displayAlbum(),
    durationMs = song.durationMs,
    art = song.toSongArt(),
)

/**
 * Spec §3's restore rule, pure: which of a saved queue's items survive, refreshed from the library.
 *
 * [library] is `MusicRepository.findByKeys` for the queue's refs — the rows that still exist under
 * a registered source. An item whose row is gone (deleted file, removed account) is dropped and the
 * index moves as [SavedQueues.pruned] describes. A survivor is REBUILT from its row rather than
 * kept as saved: a retag since the save shows its new title, and — the reason this is not
 * cosmetic — a destructive migration renumbers every surrogate id, while the art key inside a
 * saved item still holds the old one. Keeping it would hand Coil's id-keyed cache a key that may
 * now belong to a different song.
 *
 * [library] null means the database could not be read at all. Then nothing can be verified, and
 * the saved metadata is what it is for (spec §3: "enough metadata to rebuild them without a DB
 * hit"): the queue is restored as saved, and a track that really is gone surfaces as the player's
 * ordinary unplayable-item skip when it is reached.
 */
internal object QueueRestore {

    fun restorable(
        saved: SavedQueue,
        library: Map<TrackRef, Song>?,
        playableUri: (Song) -> String,
    ): SavedQueue? {
        if (library == null) return saved
        return SavedQueues.pruned(saved) { item ->
            val song = SessionMediaId.parse(item.mediaId)?.let(library::get) ?: return@pruned null
            // playableUri goes through SourceRegistry.require; a source unregistered between the
            // lookup and here is the same "no longer resolves" as a missing row.
            val uri = runCatching { playableUri(song) }.getOrNull() ?: return@pruned null
            savedItemOf(song, uri)
        }
    }

    /** Every ref [saved] names, for the one [library] lookup [restorable] needs. */
    fun refsOf(saved: SavedQueue): Set<TrackRef> =
        saved.items.mapNotNullTo(HashSet()) { SessionMediaId.parse(it.mediaId) }
}
