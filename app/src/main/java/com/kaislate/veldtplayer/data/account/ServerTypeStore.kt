// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Its own DataStore, for the reason `SyncStatusStore` has one: per-account keys that come and go
 * with accounts, unrelated to the app-wide settings.
 */
private val Context.serverTypeStore by preferencesDataStore(name = "veldt-server-types")

/**
 * What kind of server each account is — the OpenSubsonic envelope's `type` (`"navidrome"`,
 * `"gonic"`, …) — keyed by `sourceId`. It names the server tab; see [ServerTypeNames].
 *
 * **Not a column on `accounts`, on purpose.** The database uses destructive migration fallback,
 * so a schema bump to hold one short string would wipe every user's library and accounts on
 * upgrade. A value this cheap to re-learn — the next sync, or the next Test connection, writes it
 * again — belongs somewhere that costs nothing to add.
 *
 * Three states per account, and the store keeps them apart:
 * - **absent** — never learned (just added, not yet synced or tested). The tab falls back to the
 *   account's display name.
 * - **`""`** ([PLAIN]) — the server answered and named no type: an original Subsonic server.
 * - **anything else** — the type verbatim, un-normalised; [ServerTypeNames] owns the spelling.
 *
 * Written by `SubsonicSyncWorker` (from the extensions envelope it already fetches) and by
 * `AccountsViewModel` after a successful probe; cleared by `SubsonicSyncCoordinator.purge` when
 * the account is removed.
 */
@Singleton
class ServerTypeStore @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Every known account's type, `sourceId` → raw type ([PLAIN] for a plain Subsonic server).
     *
     * `distinctUntilChanged`: DataStore re-emits the whole map on every write, and a sync that
     * re-records the type it already had must not re-derive the tab label and re-lay the bar.
     */
    val types: Flow<Map<String, String>> = context.serverTypeStore.data.map { prefs ->
        prefs.asMap().entries
            .filter { (key, _) -> key.name.startsWith(PREFIX) }
            .associate { (key, value) -> key.name.removePrefix(PREFIX) to value.toString() }
    }.distinctUntilChanged()

    /** Record [sourceId]'s [serverType] — null meaning "answered, and named none" ([PLAIN]). */
    suspend fun record(sourceId: String, serverType: String?) {
        val value = serverType?.trim().orEmpty()
        context.serverTypeStore.edit { prefs -> prefs[key(sourceId)] = value }
    }

    /** Forget [sourceId] — an account being removed leaves nothing a reused id could inherit. */
    suspend fun clear(sourceId: String) {
        context.serverTypeStore.edit { prefs -> prefs.remove(key(sourceId)) }
    }

    /** Test seam: the [preferencesDataStore] delegate is process-global — see
     *  `SyncStatusStore.clearForTest`. */
    internal suspend fun clearForTest() {
        context.serverTypeStore.edit { it.clear() }
    }

    private fun key(sourceId: String) = stringPreferencesKey("$PREFIX$sourceId")

    companion object {
        private const val PREFIX = "type_"

        /** The stored value for a server that answered without a `type`. */
        const val PLAIN = ""
    }
}
