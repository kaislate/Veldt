// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The account list's order, which `AccountDao.observeAll`'s KDoc makes a promise about and
 * which nothing tested.
 *
 * The tie is not exotic: `createdAtMs` is `System.currentTimeMillis()`, so two accounts added
 * back to back land in the same millisecond. `AccountRepositoryTest`'s "two accounts on the same
 * server get different ids" creates exactly that tied pair and asserts only that the ids differ.
 *
 * Its own file rather than another method on `AccountRepositoryTest`, because the subject is the
 * SQL, not the repository: the ids and the clock are both pinned here so the expectation is a
 * statement about ordering and not about which UUID happened to sort first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountOrderingTest {

    private lateinit var db: VeldtDatabase
    private lateinit var repo: AccountRepository
    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java).allowMainThreadQueries().build()
        var n = 0
        repo = AccountRepository(
            dao = db.accountDao(),
            box = SecretBox(object : KeyProvider { override fun secretKey(): SecretKey = key }),
            files = SecretFiles(ctx),
            newId = { "acct-${++n}" },    // ordered ids, so the expectation is not UUID roulette
            now = { 1_700_000_000_000L },  // one fixed instant: every row ties on createdAtMs
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun addOk(displayName: String): String {
        val result = repo.add(displayName, "http://h:4533", "Kyle", "p")
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    @Test fun `renaming one of two accounts created in the SAME millisecond keeps the order`() = runTest {
        // `@Insert(onConflict = REPLACE)` is not a true upsert: SQLite DELETEs and re-INSERTs,
        // handing the row a new rowid, and among `createdAtMs` ties SQLite returns rows in rowid
        // order. So a rename moved the renamed account to the end — literally "the list
        // reshuffles when a display name is edited", which the DAO's KDoc says it does not.
        val first = addOk("Alpha")
        val second = addOk("Beta")
        assertEquals(listOf("acct-1", "acct-2"), listOf(first, second))

        repo.rename(first, "Alpha renamed")

        assertEquals(
            "the list reshuffled when a display name was edited",
            listOf("acct-1", "acct-2"),
            repo.observe().first().map { it.sourceId },
        )
        // ...and the rename actually happened, so the assertion above is not passing because
        // rename() is a no-op. (rename() had no test of any kind before this one.)
        assertEquals(
            listOf("Alpha renamed", "Beta"),
            repo.observe().first().map { it.displayName },
        )
    }

    @Test fun `a third account added later still sorts after the tied pair`() = runTest {
        // The tiebreaker must not become the primary key: an account created later belongs at
        // the end even when its id sorts first. `acct-3` would sort last by id anyway, so the
        // ids here are deliberately reversed against creation order.
        var m = 0
        val ordered = AccountRepository(
            dao = db.accountDao(),
            box = SecretBox(object : KeyProvider { override fun secretKey(): SecretKey = key }),
            files = SecretFiles(ApplicationProvider.getApplicationContext()),
            newId = { listOf("zz-1", "zz-2", "aa-3")[m++] },
            now = { if (m < 3) 1_700_000_000_000L else 1_700_000_000_001L },
        )
        ordered.add("Alpha", "http://h:4533", "Kyle", "p")
        ordered.add("Beta", "http://h:4533", "Kyle", "p")
        ordered.add("Gamma", "http://h:4533", "Kyle", "p")

        assertEquals(
            listOf("zz-1", "zz-2", "aa-3"),
            ordered.observe().first().map { it.sourceId },
        )
    }
}
