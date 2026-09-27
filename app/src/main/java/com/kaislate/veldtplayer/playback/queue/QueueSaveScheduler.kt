// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.playback.scrobble.Scheduler

/**
 * WHEN the queue is saved (spec §3), with the saving itself left to [save].
 *
 * - [requestSave] is debounced: a burst of player events — a queue replaced, which is a timeline
 *   change, an item transition and a play-state change inside one frame — becomes one write
 *   [debounceMs] after the last of them, not three.
 * - While playing, a save also runs every [periodMs], because the position moves without any event
 *   and a process killed outright (force-stop, low-memory kill) gets no chance to save on the way
 *   out. The most a hard kill can lose is one period of listening position.
 * - [flush] saves NOW, for `onTaskRemoved`/`onDestroy`, where there is no later.
 *
 * Driven through [Scheduler], the seam `Scrobbler`'s timer already uses, so every rule above is
 * tested with a fake clock instead of a looper.
 */
class QueueSaveScheduler(
    private val scheduler: Scheduler,
    private val save: () -> Unit,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val periodMs: Long = PERIOD_MS,
) {
    private var cancelDebounce: (() -> Unit)? = null
    private var cancelPeriodic: (() -> Unit)? = null
    private var released = false

    fun requestSave() {
        if (released) return
        cancelDebounce?.invoke()
        cancelDebounce = scheduler.postDelayed(debounceMs) {
            cancelDebounce = null
            save()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (released) return
        if (!playing) {
            cancelPeriodic?.invoke()
            cancelPeriodic = null
            return
        }
        if (cancelPeriodic == null) schedulePeriodic()
    }

    /** Saves synchronously and drops a pending debounce, which would only repeat this save. */
    fun flush() {
        if (released) return
        cancelDebounce?.invoke()
        cancelDebounce = null
        save()
    }

    /** Cancels everything and makes every later call a no-op; the service calls it after its last
     *  [flush], so a stray player event during teardown cannot schedule a write into a dead
     *  service. */
    fun release() {
        cancelDebounce?.invoke()
        cancelPeriodic?.invoke()
        cancelDebounce = null
        cancelPeriodic = null
        released = true
    }

    private fun schedulePeriodic() {
        cancelPeriodic = scheduler.postDelayed(periodMs) {
            save()
            if (!released && cancelPeriodic != null) schedulePeriodic()
        }
    }

    companion object {
        const val DEBOUNCE_MS = 1_000L

        /** Spec §3: "every 15 s while playing". */
        const val PERIOD_MS = 15_000L
    }
}
