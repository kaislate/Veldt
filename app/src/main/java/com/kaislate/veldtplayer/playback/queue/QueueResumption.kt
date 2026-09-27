// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executor

/**
 * The decision behind `PlaybackService`'s `onPlaybackResumption` (spec §3), apart from Media3 so
 * it can be tested without a session.
 *
 * - [live] — the player's own queue, snapshotted now — wins when there is one. Media3 asks for
 *   resumption when a play reaches a player with no CURRENT item, but System UI also asks for the
 *   "recent" item while a restored queue sits loaded and unprepared; the answer then is that queue
 *   as it stands, not an older copy on disk.
 * - Otherwise the answer waits for [restored] — a cold start's restore may still be reading the
 *   database when a Bluetooth "play" arrives — and is then [latest]: the restored queue, or a
 *   newer save.
 * - Neither: the future FAILS. That is how a session with nothing to resume opts out; Media3 then
 *   plays nothing, and System UI shows no resume card.
 *
 * [latest] is read on [executor] (the main thread in production) because that is the thread that
 * writes it.
 */
class QueueResumption(
    private val restored: ListenableFuture<*>,
    private val live: () -> SavedQueue?,
    private val latest: () -> SavedQueue?,
    private val executor: Executor = MoreExecutors.directExecutor(),
) {
    fun resume(): ListenableFuture<SavedQueue> {
        live()?.let { return Futures.immediateFuture(it) }
        return Futures.transform(
            restored,
            { _ -> latest() ?: throw NothingToResume() },
            executor,
        )
    }

    /** "Nothing saved", as the failure that makes Media3 opt out. */
    class NothingToResume : UnsupportedOperationException("No saved queue to resume")
}
