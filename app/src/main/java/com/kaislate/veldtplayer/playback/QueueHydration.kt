// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.library.model.Song

/** What [QueueHydration] needs from one session item: its id and the metadata it carries. */
data class SessionItemInfo(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val art: SongArt?,
)

/**
 * Rebuilds the app's `List<Song>` view of a queue from the session's items, for a queue the app
 * did not put there itself — the restored queue (spec §3), and anything a car or another controller
 * plays through the browse tree (spec §6). Without it `PlaybackConnection` has no [Song] for the
 * current index, `nowPlaying` stays EMPTY, and the mini-player is blank while a track is loaded.
 *
 * Each item becomes its library row when [found] has one — the real [Song], with every field the
 * rest of the app reads (album artist for "go to album", track numbers, the tag-merged title). An
 * item with no row still becomes a [Song], built from what the session item itself carries, so
 * the list stays index-aligned with the player's timeline: the connection indexes this list by
 * `currentMediaItemIndex`, and one missing entry would shift every later track onto the wrong
 * caption.
 *
 * The fallback's id is the art's surrogate id when there is one: that is the id the item's own art
 * uri was built from, so Coil's id-keyed art cache and the now-playing screen's `songId` lookup
 * agree with the notification about which cover this is.
 */
object QueueHydration {

    fun hydrate(items: List<SessionItemInfo>, found: Map<TrackRef, Song>): List<Song> =
        items.map { item ->
            val ref = SessionMediaId.parse(item.mediaId)
            ref?.let(found::get) ?: fallback(item, ref)
        }

    private fun fallback(item: SessionItemInfo, ref: TrackRef?): Song = Song(
        id = item.art?.songId ?: Song.UNSAVED,
        sourceId = ref?.sourceId.orEmpty(),
        externalId = ref?.externalId ?: item.mediaId,
        uri = item.art?.uri.orEmpty(),
        filePath = item.art?.filePath,
        relativeKey = null,
        title = item.title,
        artist = item.artist,
        album = item.album,
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = item.durationMs,
        dateModifiedSec = 0L,
        hasEmbeddedArt = item.art?.hasEmbeddedArt ?: false,
    )
}
