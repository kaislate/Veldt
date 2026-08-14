// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.account.db.AccountEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Add, edit, delete an account, keeping the Room row and the sealed secret in step.
 *
 * The two stores are separate on purpose (see `AccountEntity`), which makes deletion the
 * interesting operation: a delete that removes only the row leaves a user's encrypted password
 * on disk forever with nothing referencing it. `AccountRepositoryTest` asserts the file is
 * gone, not merely the row.
 *
 * [newId] and [now] are injected rather than called inline so tests are deterministic.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val dao: AccountDao,
    private val box: SecretBox,
    private val files: SecretFiles,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun observe(): Flow<List<Account>> = dao.observeAll().map { rows ->
        rows.map { row ->
            Account(
                sourceId = row.sourceId,
                displayName = row.displayName,
                baseUrl = row.baseUrl,
                username = row.username,
                // Read through the box, not merely "a file exists": an invalidated Keystore
                // key leaves the file in place and unreadable, and to a user that is the same
                // state as having no password at all.
                hasSecret = password(row.sourceId) != null,
            )
        }
    }

    suspend fun add(
        displayName: String,
        baseUrl: String,
        username: String,
        password: String,
    ): String {
        val sourceId = newId()
        box.seal(password)?.let { files.write(sourceId, it) }
        dao.upsert(
            AccountEntity(
                sourceId = sourceId,
                displayName = displayName,
                baseUrl = baseUrl,
                username = username,
                authMode = AccountEntity.AUTH_TOKEN,
                capabilities = null,
                createdAtMs = now(),
            )
        )
        return sourceId
    }

    /**
     * Change where and who, and optionally the password.
     *
     * A null [password] means "leave the stored one alone". The edit screen must be usable
     * for fixing a URL without making the user re-type a password they cannot see — that is
     * the whole point of identity surviving an edit (design spec §5.2).
     */
    suspend fun updateCredentials(
        sourceId: String,
        baseUrl: String,
        username: String,
        password: String?,
    ) {
        val existing = dao.get(sourceId) ?: return
        if (password != null) {
            box.seal(password)?.let { files.write(sourceId, it) }
        }
        dao.upsert(existing.copy(baseUrl = baseUrl, username = username))
    }

    suspend fun rename(sourceId: String, displayName: String) {
        val existing = dao.get(sourceId) ?: return
        dao.upsert(existing.copy(displayName = displayName))
    }

    suspend fun cacheCapabilities(sourceId: String, extensionNames: Collection<String>) {
        val existing = dao.get(sourceId) ?: return
        dao.upsert(existing.copy(capabilities = extensionNames.sorted().joinToString(",")))
    }

    suspend fun delete(sourceId: String) {
        dao.delete(sourceId)
        files.delete(sourceId)
    }

    /** The plaintext password, or null if there is none or the key that sealed it is gone. */
    fun password(sourceId: String): String? = files.read(sourceId)?.let { box.open(it) }
}
