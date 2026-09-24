// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.kaislate.veldtplayer.data.library.db.SongDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Servers screen's whole interface to syncing (N2 Task 3 — spec §5.4): request one, watch its
 * status, or stop and clean up after an account being removed. A plain interface — not just the
 * one concrete implementation — for the same reason
 * [com.kaislate.veldtplayer.data.library.RemoteSources] is: `AccountsViewModelTest` fakes this
 * rather than driving real `WorkManager` state to prove ordering.
 *
 * **[cancel] and [purge] are deliberately two calls, not one `forget`** (fix round 1, Important
 * finding). `WorkManager.cancelUniqueWork` is cooperative: it does not wait for a `doWork()`
 * already past its network call to stop, so a worker can still commit
 * [com.kaislate.veldtplayer.data.library.db.SongDao.replaceSourceIfPresent] after this process
 * asks it to cancel. Deleting the songs and status BEFORE the account row would race that commit
 * and could leave orphaned rows behind forever, for an id nothing will ever sync again. The
 * correct order — enforced by `AccountsViewModel.delete`, not by this interface, since deleting
 * the account row itself is [com.kaislate.veldtplayer.data.account.AccountRepository]'s job, not
 * this class's — is [cancel], then the account row, then [purge]:
 * [com.kaislate.veldtplayer.data.library.db.SongDao.replaceSourceIfPresent] checks the account row
 * INSIDE its own write transaction, so a worker transaction that commits before the account-row
 * delete gets its rows swept by the later [purge], and one that runs after sees no account and
 * writes nothing — there is no interleaving that orphans a row.
 */
interface SubsonicSync {
    /** Enqueue [sourceId]'s sync, unique per account, keeping an in-flight one rather than piling
     *  up (`ExistingWorkPolicy.KEEP`). Requires a network, same as any other Subsonic call. */
    fun request(sourceId: String)

    /** [sourceId]'s live status: whatever [SubsonicSyncWorker] last recorded, overlaid with
     *  whether its unique work is actually running right now. */
    fun status(sourceId: String): Flow<SyncStatus>

    /** Best-effort stop for [sourceId]'s sync. Cooperative only — see the class KDoc — so a caller
     *  must not treat this as a guarantee that no write will land after it returns; that guarantee
     *  comes from the delete-order this class's KDoc describes, not from this call alone. */
    fun cancel(sourceId: String)

    /** Drop every song [sourceId] contributed and its recorded status. Callers must call this
     *  only AFTER the account row itself is gone (see the class KDoc) — calling it first is the
     *  exact ordering bug fix round 1 fixed. */
    suspend fun purge(sourceId: String)
}

/** [SubsonicSync]'s real, `WorkManager`-backed implementation. */
@Singleton
class SubsonicSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val statusStore: SyncStatusStore,
) : SubsonicSync {

    override fun request(sourceId: String) {
        val request = OneTimeWorkRequestBuilder<SubsonicSyncWorker>()
            .setInputData(workDataOf(SubsonicSyncWorker.KEY_SOURCE_ID to sourceId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueWorkName(sourceId), ExistingWorkPolicy.KEEP, request)
    }

    override fun status(sourceId: String): Flow<SyncStatus> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(uniqueWorkName(sourceId))
            .combine(statusStore.status(sourceId)) { infos, stored ->
                // BLOCKED counts as running, same reasoning as MusicRepository.scanning(): to a
                // user asking "is a sync coming?" a blocked one is still coming.
                stored.copy(running = infos.any { !it.state.isFinished })
            }

    override fun cancel(sourceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(sourceId))
    }

    override suspend fun purge(sourceId: String) {
        songDao.deleteBySource(sourceId)
        statusStore.clear(sourceId)
    }

    private fun uniqueWorkName(sourceId: String) = "veldt-sync-$sourceId"
}
