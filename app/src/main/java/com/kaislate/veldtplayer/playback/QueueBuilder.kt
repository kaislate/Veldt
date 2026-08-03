// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.model.Song

/** A queue to load into the player, and where inside it to start. */
data class QueuePlan(val songs: List<Song>, val startIndex: Int)

/**
 * The queue after an append, and whether the player has to be started for it.
 *
 * [startPlayback] is the whole reason this is a plan rather than a list concatenation. "Add to
 * queue" with nothing playing has no queue to add to, and an append that silently does nothing is
 * the worst of the three possible behaviours: the user is told tracks were queued, no audio starts,
 * and there is no surface anywhere that shows them. Appending to an EMPTY player therefore starts
 * playing; appending to a live one never interrupts it.
 */
data class AppendPlan(val songs: List<Song>, val startPlayback: Boolean)

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

    /**
     * Add [incoming] after everything already queued (spec §3.2, append-to-queue), or null when
     * there is nothing to add.
     *
     * The existing queue is KEPT and the new tracks go after it — an append that replaced the queue
     * would be `build` under another name, and it is a one-character mistake to make. Duplicates
     * are not filtered: queuing a track that is already further down the queue is a thing people do
     * on purpose, and it is also the only way to hear something twice without waiting.
     *
     * Pure, because the alternative is a decision inside a method that needs a live
     * `MediaController` to reach — which is how P1.4's `setArtworkUri` came to be deletable with
     * the suite still green.
     */
    fun append(current: List<Song>, incoming: List<Song>): AppendPlan? {
        if (incoming.isEmpty()) return null
        if (current.isEmpty()) return AppendPlan(incoming, startPlayback = true)
        return AppendPlan(current + incoming, startPlayback = false)
    }
}
