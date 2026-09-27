// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.library.model.Song

/** Shared fixtures for the queue-persistence tests. `externalId` is never `id.toString()`, so a
 *  test cannot pass by confusing the surrogate with the source identity. */
internal object QueueFixtures {

    fun item(n: Int, sourceId: String = "local") = SavedItem(
        mediaId = "$sourceId:ext-$n",
        uri = "content://media/external/audio/media/${n + 500}",
        title = "Title $n",
        artist = "Artist $n",
        album = "Album $n",
        durationMs = 1_000L * n,
        art = SongArt(songId = n.toLong(), uri = "content://media/external/audio/media/${n + 500}",
            filePath = "/music/$n.mp3", hasEmbeddedArt = n % 2 == 0),
    )

    fun queue(
        size: Int,
        index: Int = 0,
        positionMs: Long = 0L,
        shuffleOrder: List<Int>? = null,
    ) = SavedQueue(
        items = (0 until size).map { item(it) },
        index = index,
        positionMs = positionMs,
        shuffleEnabled = shuffleOrder != null,
        shuffleOrder = shuffleOrder,
        repeatMode = 0,
    )

    fun song(
        n: Int,
        sourceId: String = "local",
        id: Long = n.toLong(),
        title: String = "Fresh $n",
    ) = Song(
        id = id,
        sourceId = sourceId,
        externalId = "ext-$n",
        uri = "content://media/external/audio/media/${n + 500}",
        filePath = "/music/$n.mp3",
        relativeKey = "external_primary:Music/$n.mp3",
        title = title,
        artist = "Artist $n",
        album = "Album $n",
        albumArtist = null,
        trackNumber = n,
        discNumber = 1,
        year = 2001,
        durationMs = 2_000L * n,
        dateModifiedSec = 0L,
        hasEmbeddedArt = true,
    )
}
