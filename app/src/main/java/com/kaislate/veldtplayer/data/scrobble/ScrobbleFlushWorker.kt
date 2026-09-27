// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one retry job (design spec §5, amended 2026-09-27): "whenever the queue becomes non-empty
 * and not auth-blocked, enqueue unique work `veldt-scrobble-flush`". [doWork] flushes every
 * source once per attempt. A device measurement showed mobile data satisfying
 * [NetworkType.CONNECTED] while a LAN/Tailscale-only server stays unreachable — the ORIGINAL
 * "always success, never retry" design stranded the entry until the next piggyback. The
 * amendment: when [ScrobbleFlusher.flushAll] reports [FlushOutcome.UNREACHABLE], retry with
 * WorkManager's own exponential backoff ([enqueue]'s [BackoffPolicy.EXPONENTIAL], 30 s initial),
 * capped at [MAX_ATTEMPTS] attempts total ([runAttemptCount] 0..[MAX_ATTEMPTS] - 1) — the 6th
 * attempt (`runAttemptCount == 5`) returns success and stops even if still unreachable, so a
 * server that is down for good does not retry forever. [FlushOutcome.DELIVERED] (everything
 * delivered, an auth-block, an unknown/purged source, or an empty queue) always returns success
 * immediately — see [FlushOutcome]'s KDoc for why this must NOT be inferred from "queue empty".
 */
@HiltWorker
class ScrobbleFlushWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val flusher: ScrobbleFlusher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val outcome = flusher.flushAll()
        if (outcome != FlushOutcome.UNREACHABLE) return Result.success()
        // runAttemptCount is 0 on the first attempt; the 6th attempt (index 5) is the last one
        // allowed to retry — on it, give up and report success instead.
        return if (runAttemptCount >= MAX_ATTEMPTS - 1) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "veldt-scrobble-flush"

        /** Amendment 2026-09-27: at most 6 attempts total (`runAttemptCount` 0..5) before the
         *  worker gives up on an unreachable server and returns success. */
        private const val MAX_ATTEMPTS = 6

        /** Amendment 2026-09-27: [BackoffPolicy.EXPONENTIAL] starting at 30 s, applied to every
         *  [Result.retry] this worker returns for an unreachable server. */
        private const val INITIAL_BACKOFF_SECONDS = 30L

        /** [Constraints]/[BackoffPolicy] shared by [enqueue] and (directly, so a test can inspect
         *  the built [androidx.work.WorkRequest.workSpec] without going through a real
         *  [WorkManager] instance) `ScrobbleFlushWorkerTest`. */
        fun buildRequest() =
            OneTimeWorkRequestBuilder<ScrobbleFlushWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, INITIAL_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        /** Unique, [ExistingWorkPolicy.KEEP] (an already-running or already-queued attempt is
         *  left alone rather than piled on), requires a network — same shape as
         *  [com.kaislate.veldtplayer.data.library.sync.SubsonicSyncCoordinator.request]. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, buildRequest())
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
