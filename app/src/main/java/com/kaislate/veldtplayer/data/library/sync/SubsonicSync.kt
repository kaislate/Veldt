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
 * status, or drop everything about an account being removed. A plain interface — not just the one
 * concrete implementation — for the same reason [com.kaislate.veldtplayer.data.library.RemoteSources]
 * is: `AccountsViewModelTest` fakes this rather than driving real `WorkManager` state to prove
 * ordering ("forget before the row vanishes").
 */
interface SubsonicSync {
    /** Enqueue [sourceId]'s sync, unique per account, keeping an in-flight one rather than piling
     *  up (`ExistingWorkPolicy.KEEP`). Requires a network, same as any other Subsonic call. */
    fun request(sourceId: String)

    /** [sourceId]'s live status: whatever [SubsonicSyncWorker] last recorded, overlaid with
     *  whether its unique work is actually running right now. */
    fun status(sourceId: String): Flow<SyncStatus>

    /** An account being removed: cancel any in-flight or pending sync, drop every row it
     *  contributed to the library, and forget its recorded status — nothing should survive that
     *  a stale id could later resurrect the appearance of. */
    suspend fun forget(sourceId: String)
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

    override suspend fun forget(sourceId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(sourceId))
        songDao.deleteBySource(sourceId)
        statusStore.clear(sourceId)
    }

    private fun uniqueWorkName(sourceId: String) = "veldt-sync-$sourceId"
}
