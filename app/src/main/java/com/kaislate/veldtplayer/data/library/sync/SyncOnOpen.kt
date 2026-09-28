// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import com.kaislate.veldtplayer.data.account.db.AccountDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When an account's catalogue is old enough to re-sync on app open (owner decision, round 4).
 * Pure, so the threshold and its edges are unit tests.
 */
object SyncStaleness {

    /** Fifteen minutes: often enough that a server edit shows up the next time the app is opened
     *  after a break, rarely enough that flicking between apps does not re-fetch a whole catalogue
     *  — a sync is one request per album, so it is not free for a large library. */
    const val STALE_AFTER_MS: Long = 15 * 60 * 1000L

    /**
     * True when [lastSuccessMs] is absent (never synced — including an account whose every sync
     * so far failed) or at least [staleAfterMs] before [nowMs]. A last success in the FUTURE is
     * stale too: that is a clock that was moved back, and trusting the stamp would block syncing
     * until the clock caught up with it.
     */
    fun isStale(lastSuccessMs: Long?, nowMs: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
        lastSuccessMs == null || lastSuccessMs > nowMs || nowMs - lastSuccessMs >= staleAfterMs
}

/**
 * Re-sync stale server accounts when Veldt comes to the foreground (owner decision, round 4:
 * "on app open"; still no periodic background sync). Called from `MainActivity.onStart`, which
 * covers both a cold start and returning to a live process.
 *
 * Cheap to call often, which is why it can hang off `onStart` (a rotation calls it again): zero
 * accounts is one indexed SELECT and no request at all — the offline-by-default promise — and a
 * fresh account is skipped by [SyncStaleness]. A stale one goes through [SubsonicSync.request],
 * whose KEEP policy leaves a sync already queued or running alone, and whose network constraint
 * makes an offline open a no-op until a network appears.
 */
@Singleton
class SyncOnOpen @Inject constructor(
    private val accountDao: AccountDao,
    private val status: SyncStatusStore,
    private val sync: SubsonicSync,
) {
    suspend fun run(nowMs: Long = System.currentTimeMillis()) {
        for (account in accountDao.getAll()) {
            val last = status.status(account.sourceId).first().lastSuccessMs
            if (SyncStaleness.isStale(last, nowMs)) sync.request(account.sourceId)
        }
    }
}
