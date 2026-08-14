// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One configured server account.
 *
 * [sourceId] is the primary key AND the id this account will register under as a
 * `LibrarySource` in N2 — a random UUID minted at creation, never derived from url+username
 * (design spec §5.2). It must contain no `':'` or `'/'`; a UUID contains neither, and
 * `AccountRepositoryTest` pins that rather than trusting it.
 *
 * **No secret column, deliberately.** The password lives sealed in an app-private file keyed
 * by [sourceId]; see `SecretFiles`. Keeping it out of the table means no query, debug dump, or
 * future schema export can carry it.
 *
 * [authMode] is stored even though only `TOKEN` ships. Navidrome 0.63.2 does not implement
 * API-key auth at all — measured 2026-08-14 — but other servers do, and a column is cheaper
 * than a migration.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val sourceId: String,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val authMode: String,
    /** Extension names last seen, comma-separated. A cache, never a source of truth. */
    val capabilities: String?,
    val createdAtMs: Long,
) {
    companion object {
        const val AUTH_TOKEN = "TOKEN"
    }
}
