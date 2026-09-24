// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a server account's last sync left behind. [running] is never this store's business — it
 * is WorkManager's, and [SubsonicSync.status] is what overlays it onto the rest.
 */
data class SyncStatus(
    val running: Boolean,
    val lastSuccessMs: Long?,
    val songCount: Int?,
    val lastError: String?,
)

/** Its own store, deliberately separate from `SettingsRepository`'s `veldt-settings` — sync
 *  status is per-account, keeps growing new keys as accounts are added, and has nothing to do
 *  with the app-wide settings this shares a mechanism with. */
private val Context.syncStatusStore by preferencesDataStore(name = "veldt-sync")

/**
 * Persists one [SyncStatus] per account (minus [SyncStatus.running]), keyed by `sourceId` inside
 * the shared DataStore. [recordSuccess] clears any stale [SyncStatus.lastError] — a server that
 * used to reject a password and now accepts it must not go on showing the old banner.
 */
@Singleton
class SyncStatusStore @Inject constructor(@ApplicationContext private val context: Context) {

    /** [SyncStatus.running] is always false here — [SubsonicSync.status] is the one place that
     *  knows whether work is actually in flight. */
    fun status(sourceId: String): Flow<SyncStatus> = context.syncStatusStore.data.map { prefs ->
        SyncStatus(
            running = false,
            lastSuccessMs = prefs[lastOkKey(sourceId)],
            songCount = prefs[countKey(sourceId)],
            lastError = prefs[errorKey(sourceId)],
        )
    }

    suspend fun recordSuccess(sourceId: String, atMs: Long, songCount: Int) {
        context.syncStatusStore.edit { prefs ->
            prefs[lastOkKey(sourceId)] = atMs
            prefs[countKey(sourceId)] = songCount
            prefs.remove(errorKey(sourceId))
        }
    }

    /** Fixed, non-credential-bearing wording only — see the worker's call sites for why. */
    suspend fun recordError(sourceId: String, message: String) {
        context.syncStatusStore.edit { prefs -> prefs[errorKey(sourceId)] = message }
    }

    /** [SubsonicSync.forget] calls this for an account being removed: nothing should remain that
     *  a stale account id could resurrect the appearance of a status for. */
    suspend fun clear(sourceId: String) {
        context.syncStatusStore.edit { prefs ->
            prefs.remove(lastOkKey(sourceId))
            prefs.remove(countKey(sourceId))
            prefs.remove(errorKey(sourceId))
        }
    }

    /** Test seam: see `SettingsRepository.clearForTest` for why this exists — the
     *  [preferencesDataStore] delegate is process-global, so one test's writes would otherwise
     *  leak into the next. */
    internal suspend fun clearForTest() {
        context.syncStatusStore.edit { it.clear() }
    }

    private fun lastOkKey(sourceId: String) = longPreferencesKey("last_ok_$sourceId")
    private fun countKey(sourceId: String) = intPreferencesKey("count_$sourceId")
    private fun errorKey(sourceId: String) = stringPreferencesKey("error_$sourceId")
}
