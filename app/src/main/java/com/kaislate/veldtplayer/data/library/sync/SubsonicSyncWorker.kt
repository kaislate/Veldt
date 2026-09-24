// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.toEntity
import com.kaislate.veldtplayer.data.net.CatalogResult
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.data.net.fetchCatalog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One account's catalog sync (N2 Task 3 — spec §5.4). Enqueued only by
 * [SubsonicSync.request] — on account add, on a credential/URL change, and by the Servers
 * screen's Refresh button. There is deliberately no periodic work and no on-app-open sync (owner
 * decision): zero accounts means zero requests, and an existing account only re-syncs when
 * something about it changed or the user asked.
 *
 * The order below is the whole contract and is spelled out rather than left to be inferred:
 *
 * 1. [AccountDao.get] — the account row itself may be gone (a race with delete on another
 *    screen); that is [FAILURE_GONE], not [FAILURE_AUTH].
 * 2. [SubsonicSources.credentials] — null here means the secret cannot be read (the Keystore
 *    invalidated its key, or it was never sealed): [FAILURE_AUTH], because from the sync's point
 *    of view it is exactly as unusable as a rejected password, and the account screen already
 *    tells the user to re-enter it in that case.
 * 3. [SubsonicClient.capabilities] is fetched and cached via [AccountRepository.cacheCapabilities]
 *    BEFORE the catalog call, unconditionally — even a sync that goes on to fail has still learned
 *    what the server can do, and the next attempt should not re-probe for it.
 * 4. [fetchCatalog]. [CatalogResult.Ok] calls [SongDao.replaceSourceIfPresent], which refuses to
 *    write anything for an account row that no longer exists — see that method's KDoc for why a
 *    plain [SongDao.replaceSource] here would be a data-loss bug (fix round 1, Important
 *    finding): a null result is [FAILURE_GONE], not a success. A [CatalogResult.Rejected] whose
 *    [com.kaislate.veldtplayer.data.net.SubsonicError.meansCredentialsWontWork] is a definitive
 *    [FAILURE_AUTH] — retrying with the same password cannot succeed. Anything else transient
 *    (a dead socket, a 500, an unclassified rejection) retries up to [MAX_ATTEMPTS], **never**
 *    touching the stored songs — a sync that cannot reach the server must leave the library
 *    exactly as it was, not empty it.
 *
 * [now] exists only so `SubsonicSyncWorkerTest` can pin [SyncStatus.lastSuccessMs]; the
 * `@AssistedInject` constructor Hilt uses supplies the real clock, for the same reason
 * `AccountRepository`'s two-constructor split exists — Dagger cannot see a Kotlin default value.
 */
@HiltWorker
class SubsonicSyncWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val client: SubsonicClient,
    private val sources: SubsonicSources,
    private val accounts: AccountRepository,
    private val accountDao: AccountDao,
    private val songDao: SongDao,
    private val status: SyncStatusStore,
    private val now: () -> Long,
) : CoroutineWorker(appContext, params) {

    @AssistedInject constructor(
        @Assisted appContext: Context,
        @Assisted params: WorkerParameters,
        client: SubsonicClient,
        sources: SubsonicSources,
        accounts: AccountRepository,
        accountDao: AccountDao,
        songDao: SongDao,
        status: SyncStatusStore,
    ) : this(
        appContext = appContext,
        params = params,
        client = client,
        sources = sources,
        accounts = accounts,
        accountDao = accountDao,
        songDao = songDao,
        status = status,
        now = { System.currentTimeMillis() },
    )

    override suspend fun doWork(): Result {
        val sourceId = inputData.getString(KEY_SOURCE_ID) ?: return Result.failure(failureData(FAILURE_GONE))
        accountDao.get(sourceId) ?: return Result.failure(failureData(FAILURE_GONE))
        val creds = sources.credentials(sourceId) ?: return Result.failure(failureData(FAILURE_AUTH))

        val caps = client.capabilities(creds.baseUrl)
        accounts.cacheCapabilities(sourceId, caps.extensions.keys)

        return when (val result = client.fetchCatalog(sourceId, creds, caps)) {
            is CatalogResult.Ok -> {
                // replaceSourceIfPresent, never replaceSource: cancelUniqueWork is cooperative and
                // does not stop a worker already past this point, so the account row could have
                // been deleted while fetchCatalog was in flight. Writing rows for a sourceId whose
                // account is gone would orphan them forever — nothing ever syncs that id again.
                val plan = songDao.replaceSourceIfPresent(sourceId, result.songs.map { it.toEntity() })
                if (plan == null) {
                    Result.failure(failureData(FAILURE_GONE))
                } else {
                    status.recordSuccess(sourceId, now(), result.songs.size)
                    Result.success()
                }
            }
            is CatalogResult.Rejected ->
                if (result.error.meansCredentialsWontWork) {
                    status.recordError(sourceId, MESSAGE_BAD_CREDENTIALS)
                    Result.failure(failureData(FAILURE_AUTH))
                } else {
                    retryOrFail(sourceId)
                }
            is CatalogResult.Unreachable -> retryOrFail(sourceId)
        }
    }

    /** Never touches [songDao] — an unreachable server must leave the library exactly as it
     *  was, not empty it. */
    private suspend fun retryOrFail(sourceId: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) {
            Result.retry()
        } else {
            status.recordError(sourceId, MESSAGE_UNREACHABLE)
            Result.failure(failureData(FAILURE_UNREACHABLE))
        }

    private fun failureData(reason: String) = workDataOf(KEY_FAILURE to reason)

    companion object {
        const val KEY_SOURCE_ID = "source_id"
        const val KEY_FAILURE = "failure"

        const val FAILURE_AUTH = "auth"
        const val FAILURE_UNREACHABLE = "unreachable"
        const val FAILURE_GONE = "gone"

        /** Retries while `runAttemptCount < MAX_ATTEMPTS`; the attempt AT [MAX_ATTEMPTS] fails
         *  for good rather than retrying — matched by `SubsonicSyncWorkerTest`. */
        private const val MAX_ATTEMPTS = 2

        // Fixed wording only (Global Constraint 6): never a server-supplied string.
        private const val MESSAGE_BAD_CREDENTIALS = "The server rejected the saved password."
        private const val MESSAGE_UNREACHABLE = "Could not reach the server."
    }
}
