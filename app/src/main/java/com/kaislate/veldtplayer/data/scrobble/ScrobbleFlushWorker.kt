// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one retry job (design spec §5): "whenever the queue becomes non-empty and not
 * auth-blocked, enqueue unique work `veldt-scrobble-flush`". [doWork] flushes every source once
 * and ALWAYS returns [Result.success] — never [Result.retry] — because the retry policy here is
 * "the next piggyback or a later failure re-arms it" (plan Global Constraint 5: the flush worker
 * never retries itself), not WorkManager's own backoff. Leaving entries queued after a run is not
 * this worker's failure to report; it is [ScrobbleFlusher.flush] stopping at the first
 * unreachable result, exactly as designed.
 */
@HiltWorker
class ScrobbleFlushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flusher: ScrobbleFlusher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        flusher.flushAll()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "veldt-scrobble-flush"

        /** Unique, [ExistingWorkPolicy.KEEP] (an already-running or already-queued attempt is
         *  left alone rather than piled on), requires a network — same shape as
         *  [com.kaislate.veldtplayer.data.library.sync.SubsonicSyncCoordinator.request]. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScrobbleFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}

/**
 * [ScrobbleFlushWorker.enqueue], behind an interface — the same reason [com.kaislate.veldtplayer
 * .data.library.sync.SubsonicSync] wraps `WorkManager` for [SubsonicSyncCoordinator]'s callers:
 * so a caller that just wants to say "something is queued now, go arrange a retry" (Task 3's
 * `Scrobbler`, and `AccountsViewModel` on a new password — controller ruling, task 2) can be
 * tested with a fake instead of driving real `WorkManager` state.
 */
interface ScrobbleFlushScheduler {
    fun enqueue()
}

@Singleton
class WorkManagerScrobbleFlushScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ScrobbleFlushScheduler {
    override fun enqueue() = ScrobbleFlushWorker.enqueue(context)
}
