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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountRepositoryTest {

    private lateinit var db: VeldtDatabase
    private lateinit var repo: AccountRepository
    private lateinit var files: SecretFiles
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java).allowMainThreadQueries().build()
        files = SecretFiles(ctx)
        repo = AccountRepository(
            dao = db.accountDao(),
            box = SecretBox(object : KeyProvider { override fun secretKey(): SecretKey? = key }),
            files = files,
            newId = { java.util.UUID.randomUUID().toString() },
        )
    }

    @After fun tearDown() = db.close()

    @Test fun `an added account is observable and its password round trips`() = runTest {
        val id = repo.add("Home", "http://192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(
            listOf(Account(id, "Home", "http://192.168.50.111:4533", "Kyle", hasSecret = true)),
            repo.observe().first(),
        )
        assertEquals("hunter2", repo.password(id))
    }

    @Test fun `the minted source id is a UUID and is legal for SourceRegistry`() = runTest {
        val id = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        // SourceRegistry's constructor rejects ':' and '/' because they are the separators in
        // the media id and the veldt:// uri. Asserted here rather than assumed, because the
        // failure mode is a crash at app start once N2 registers this account as a source.
        assertTrue("source id contains ':' — $id", ':' !in id)
        assertTrue("source id contains '/' — $id", '/' !in id)
        assertTrue("source id is blank", id.isNotBlank())
        assertEquals(36, id.length)
    }

    @Test fun `two accounts on the same server get different ids`() = runTest {
        // Identity is minted, not derived from url+username — design spec §5.2. Deriving it
        // would make a reverse proxy or a Tailscale rename look like a different server.
        val a = repo.add("A", "http://h:4533", "Kyle", "p")
        val b = repo.add("B", "http://h:4533", "Kyle", "p")
        assertNotEquals(a, b)
    }

    @Test fun `editing the url and username keeps the identity`() = runTest {
        val id = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        repo.updateCredentials(id, "https://music.example.com", "kyle2", password = null)
        val account = repo.observe().first().single()
        assertEquals(id, account.sourceId)
        assertEquals("https://music.example.com", account.baseUrl)
        assertEquals("kyle2", account.username)
        // A null password means "leave it alone" — the edit screen must not force a re-type.
        assertEquals("hunter2", repo.password(id))
    }

    @Test fun `a non-null password on edit replaces the stored one`() = runTest {
        val id = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        repo.updateCredentials(id, "http://h:4533", "Kyle", password = "newpass")
        assertEquals("newpass", repo.password(id))
    }

    @Test fun `deleting an account removes the ROW AND the secret file`() = runTest {
        val id = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        assertTrue("no secret was written", files.read(id) != null)
        repo.delete(id)
        assertEquals(emptyList<Account>(), repo.observe().first())
        // The row and the file are two stores; deleting only the row leaves a user's password
        // encrypted on disk forever with nothing referencing it.
        assertNull("the secret file outlived its account", files.read(id))
        assertNull(repo.password(id))
    }

    @Test fun `a lost key degrades to no password rather than throwing`() = runTest {
        val id = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        key = null // the OS-update / invalidated-Keystore case
        assertNull(repo.password(id))
        // The account itself must still be listed, so the UI can offer "sign in again".
        assertEquals(listOf(id), repo.observe().first().map { it.sourceId })
        assertEquals(false, repo.observe().first().single().hasSecret)
    }

    @Test fun `password of an unknown id is null, not an error`() = runTest {
        assertNull(repo.password("no-such-account"))
    }
}
