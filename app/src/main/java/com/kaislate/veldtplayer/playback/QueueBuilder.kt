package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.model.Song

/** A queue to load into the player, and where inside it to start. */
data class QueuePlan(val songs: List<Song>, val startIndex: Int)

/**
 * Play-in-context (spec §5): tapping a song plays THE LIST IT WAS TAPPED IN, not just
 * that one track. Songs tab queues the whole library; album detail queues the album;
 * search queues the results. This is the difference between a demo and a player.
 *
 * Pure, so the clamping rules are provable without a device.
 */
object QueueBuilder {

    fun build(songs: List<Song>, tappedIndex: Int): QueuePlan {
        if (songs.isEmpty()) return QueuePlan(emptyList(), 0)
        return QueuePlan(songs, tappedIndex.coerceIn(0, songs.lastIndex))
    }
}
