// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.account.ServerAccount
import com.kaislate.veldtplayer.data.library.sync.SubsonicSyncWorker
import com.kaislate.veldtplayer.data.library.sync.SyncStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One pull-to-refresh on the server tab (round 5), start to finish, as a suspend function whose
 * lifetime IS the indicator's: the caller shows the spinner, calls [refresh], and hides it when
 * this returns — then shows the returned message, if any.
 *
 * Round 4 drove the indicator from the syncs' `running` state instead, which counts ENQUEUED: a
 * pull with no network left the spinner turning until the network returned, because the work
 * sits enqueued behind its CONNECTED constraint. Now the indicator follows the pull:
 * - **offline at pull time** → return at once with [OFFLINE]. The sync is still requested, so
 *   WorkManager runs it when the network returns.
 * - **online** → wait until every requested sync has finished, success or failure. A failure the
 *   worker recorded ([SubsonicSyncWorker.MESSAGE_UNREACHABLE] / [SubsonicSyncWorker
 *   .MESSAGE_BAD_CREDENTIALS]) becomes a one-line reason naming the server.
 * - **[timeoutMs] passes first** → return with [STILL_SYNCING]; the work carries on.
 *
 * All collaborators are plain functions so the decisions are unit tests under virtual time
 * (`ServerRefreshTest`), with no WorkManager or ConnectivityManager behind them.
 */
class ServerRefresh(
    private val isOnline: () -> Boolean,
    private val request: (sourceId: String) -> Unit,
    private val status: (sourceId: String) -> Flow<SyncStatus>,
    private val timeoutMs: Long = TIMEOUT_MS,
) {

    /**
     * Sync [targets] and wait as described in the class KDoc. [nameOf] is how a failure names the
     * server — the tab label when there is one account, the account's own name when several
     * would make the label ("Servers") ambiguous. Returns the snackbar text, or null for a
     * clean finish.
     */
    suspend fun refresh(targets: List<ServerAccount>, nameOf: (ServerAccount) -> String): String? {
        if (targets.isEmpty()) return null
        // Snapshots BEFORE the request, so a finish can be told apart from the state a previous
        // sync left behind (its lastSuccessMs, its lastError).
        val before = targets.associate { it.sourceId to status(it.sourceId).first() }
        targets.forEach { request(it.sourceId) }
        if (!isOnline()) return OFFLINE

        val finals = withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                targets.map { account ->
                    async { account to awaitFinished(account.sourceId, before.getValue(account.sourceId)) }
                }.awaitAll()
            }
        } ?: return STILL_SYNCING

        return finals.firstNotNullOfOrNull { (account, final) -> failureMessage(final, nameOf(account)) }
    }

    /**
     * The first status that shows [sourceId]'s requested sync over. "Over" is either:
     * - a running → not-running transition (the ordinary path: queued, ran, finished), or
     * - a new result recorded — [SyncStatus.lastSuccessMs] advanced, or a [SyncStatus.lastError]
     *   appeared or changed — which also covers a sync so quick that no running emission was
     *   ever observed.
     */
    private suspend fun awaitFinished(sourceId: String, before: SyncStatus): SyncStatus {
        var sawRunning = false
        return status(sourceId).first { now ->
            if (now.running) sawRunning = true
            val newResult = now.lastSuccessMs != before.lastSuccessMs ||
                (now.lastError != null && now.lastError != before.lastError)
            (sawRunning && !now.running) || (newResult && !now.running)
        }
    }

    companion object {
        /** How long the indicator waits for a sync before letting it carry on unwatched. */
        const val TIMEOUT_MS: Long = 30_000L

        const val OFFLINE = "You're offline. Veldt will sync when you're back online."
        const val STILL_SYNCING = "Still syncing in the background."

        /**
         * The reason a finished sync failed, or null if it did not. A success clears the recorded
         * error (`SyncStatusStore.recordSuccess`), so a non-null error on a finished sync is this
         * sync's own failure.
         */
        fun failureMessage(final: SyncStatus, name: String): String? = when (final.lastError) {
            null -> null
            SubsonicSyncWorker.MESSAGE_UNREACHABLE -> "Couldn't reach $name."
            SubsonicSyncWorker.MESSAGE_BAD_CREDENTIALS -> "$name rejected the saved password."
            else -> "Couldn't sync $name."
        }
    }
}
