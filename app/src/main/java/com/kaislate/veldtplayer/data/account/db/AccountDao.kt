// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    /**
     * Ordered by creation so the list does not reshuffle when a display name is edited.
     *
     * **`sourceId` is the tiebreaker and it is load-bearing.** `createdAtMs` is
     * `System.currentTimeMillis()`, so two accounts added back to back tie on it, and
     * `@Insert(onConflict = REPLACE)` is not a true upsert: SQLite deletes and re-inserts, which
     * hands the row a new rowid. Among ties SQLite returns rows in rowid order, so without this
     * clause a `rename()` moved the renamed account to the end — the exact reshuffle the first
     * line of this comment promises does not happen.
     */
    @Query("SELECT * FROM accounts ORDER BY createdAtMs ASC, sourceId ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): AccountEntity?

    /**
     * A one-shot read of every account, for [com.kaislate.veldtplayer.data.library.SubsonicSources]'
     * synchronous construction-time snapshot — see that class for why it cannot wait on [observeAll]'s
     * first emission. Order is not asserted on by anything that reads this; sort at the call site if
     * one ever needs to.
     */
    @Query("SELECT * FROM accounts")
    suspend fun getAll(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("DELETE FROM accounts WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}
