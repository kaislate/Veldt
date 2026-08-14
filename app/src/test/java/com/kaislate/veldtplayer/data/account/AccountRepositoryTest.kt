// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
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

    private lateinit var ctx: Context
    private lateinit var db: VeldtDatabase
    private lateinit var repo: AccountRepository
    private lateinit var files: SecretFiles
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun box() = SecretBox(object : KeyProvider { override fun secretKey(): SecretKey? = key })

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java).allowMainThreadQueries().build()
        files = SecretFiles(ctx)
        repo = AccountRepository(
            dao = db.accountDao(),
            box = box(),
            files = files,
            newId = { java.util.UUID.randomUUID().toString() },
        )
    }

    @After fun tearDown() = db.close()

    /** The id of an add that was expected to succeed outright. */
    private suspend fun AccountRepository.addOk(
        displayName: String,
        baseUrl: String,
        username: String,
        password: String,
    ): String {
        val result = add(displayName, baseUrl, username, password)
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    @Test fun `an added account is observable and its password round trips`() = runTest {
        val id = repo.addOk("Home", "http://192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(
            listOf(Account(id, "Home", "http://192.168.50.111:4533", "Kyle", hasSecret = true)),
            repo.observe().first(),
        )
        assertEquals("hunter2", repo.password(id))
    }

    @Test fun `the minted source id is a UUID and is legal for SourceRegistry`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        // SourceRegistry's constructor rejects ':' and '/' because they are the separators in
        // the media id and the veldt:// uri. Asserted here rather than assumed, because the
        // failure mode is a crash at app start once N2 registers this account as a source.
        assertTrue("source id contains ':' — $id", ':' !in id)
        assertTrue("source id contains '/' — $id", '/' !in id)
        assertTrue("source id is blank", id.isNotBlank())
        assertEquals(36, id.length)
    }

    @Test fun `the id minted by the PRODUCTION constructor is accepted by SourceRegistry`() = runTest {
        // The test above injects its own `newId` lambda, so everything it asserts is a JDK
        // guarantee about UUID.randomUUID() — the production minting expression is never run.
        // The Hilt fix duplicated that expression into the three-argument @Inject constructor,
        // so there are two copies and this is the only test that reaches either.
        val production = AccountRepository(db.accountDao(), box(), files)
        val id = production.addOk("Home", "http://h:4533", "Kyle", "hunter2")

        // Not a re-statement of the ':'/'/' rules: the rules themselves are run, by handing the
        // minted id to the very constructor that will see it at app start once N2 registers this
        // account as a source. A `require` failure here is the crash, in a test.
        val registry = SourceRegistry(setOf(sourceWithId(id)))
        assertEquals(id, registry.require(id).id)
    }

    @Test fun `two accounts on the same server get different ids`() = runTest {
        // Identity is minted, not derived from url+username — design spec §5.2. Deriving it
        // would make a reverse proxy or a Tailscale rename look like a different server.
        val a = repo.addOk("A", "http://h:4533", "Kyle", "p")
        val b = repo.addOk("B", "http://h:4533", "Kyle", "p")
        assertNotEquals(a, b)
    }

    @Test fun `editing the url and username keeps the identity`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        repo.updateCredentials(id, "https://music.example.com", "kyle2", password = null)
        val account = repo.observe().first().single()
        assertEquals(id, account.sourceId)
        assertEquals("https://music.example.com", account.baseUrl)
        assertEquals("kyle2", account.username)
        // A null password means "leave it alone" — the edit screen must not force a re-type.
        assertEquals("hunter2", repo.password(id))
    }

    @Test fun `a non-null password on edit replaces the stored one`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        repo.updateCredentials(id, "http://h:4533", "Kyle", password = "newpass")
        assertEquals("newpass", repo.password(id))
    }

    @Test fun `deleting an account removes the ROW AND the secret file`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        assertTrue("no secret was written", files.read(id) != null)
        repo.delete(id)
        assertEquals(emptyList<Account>(), repo.observe().first())
        // The row and the file are two stores; deleting only the row leaves a user's password
        // encrypted on disk forever with nothing referencing it.
        assertNull("the secret file outlived its account", files.read(id))
        assertNull(repo.password(id))
    }

    @Test fun `a lost key degrades to no password rather than throwing`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        key = null // the OS-update / invalidated-Keystore case
        assertNull(repo.password(id))
        // The account itself must still be listed, so the UI can offer "sign in again".
        assertEquals(listOf(id), repo.observe().first().map { it.sourceId })
        assertEquals(false, repo.observe().first().single().hasSecret)
    }

    @Test fun `password of an unknown id is null, not an error`() = runTest {
        assertNull(repo.password("no-such-account"))
    }

    // ---- I8: password() is suspend and does its work off the caller's thread ----

    @Test fun `password does its file read and decrypt OFF the calling thread`() = runTest {
        // `observe()`'s flowOn covers observe() and nothing else. password() is a file read, an
        // AES-GCM decrypt and a Keystore round trip, and it is what the request path calls per
        // request and what a "sign in again" click handler calls directly — on the caller's
        // thread that is a StrictMode disk read on main. The brief declared it `suspend`; the
        // shipped signature did not, which is the Task 5 spec deviation.
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watched = AccountRepository(
            dao = db.accountDao(),
            box = SecretBox(object : KeyProvider {
                override fun secretKey(): SecretKey? {
                    seen += Thread.currentThread().name
                    return key
                }
            }),
            files = files,
            newId = { java.util.UUID.randomUUID().toString() },
        )
        val id = watched.addOk("Home", "http://h:4533", "Kyle", "hunter2")

        seen.clear()
        val caller = Thread.currentThread().name
        assertEquals("hunter2", watched.password(id))

        // Named, not counted: the failure message has to say which thread did the disk read.
        assertEquals(
            "the decrypt ran on the caller's thread",
            emptyList<String>(),
            seen.toList().filter { it == caller },
        )
        assertTrue("the key was never consulted, so this test proves nothing", seen.isNotEmpty())
    }

    // ---- C1: the stored base URL can never carry a credential ----

    @Test fun `add strips userinfo from the base url instead of storing a password`() = runTest {
        // An ordinary thing for a self-hoster behind a basic-auth reverse proxy to type. Stored
        // verbatim it puts a cleartext password in accounts.baseUrl, into every debug DB dump,
        // and into the request authority of every OkHttp call.
        val id = repo.addOk("Home", "https://kyle:hunter2@music.example.com", "Kyle", "hunter2")
        val row = db.accountDao().get(id) ?: error("no row was written")
        assertEquals("https://music.example.com", row.baseUrl)
        // The whole ROW, not just baseUrl: a future column must not become the new hiding place.
        val rendered = row.toString()
        val leaked = listOf("kyle:", "hunter2@", "kyle:hunter2").filter { it in rendered }
        assertEquals("a credential survived into the account row: $rendered", emptyList<String>(), leaked)
    }

    @Test fun `updateCredentials strips userinfo from the base url too`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        repo.updateCredentials(id, "https://kyle:hunter2@music.example.com", "Kyle", password = null)
        val row = db.accountDao().get(id) ?: error("the row vanished")
        assertEquals("https://music.example.com", row.baseUrl)
        val rendered = row.toString()
        val leaked = listOf("kyle:", "hunter2@", "kyle:hunter2").filter { it in rendered }
        assertEquals("a credential survived into the account row: $rendered", emptyList<String>(), leaked)
    }

    @Test fun `an unusable address writes no row at all, from either method`() = runTest {
        // "do not silently store a broken row" — the alternative to normalising is a row whose
        // baseUrl no request can ever be built from.
        assertEquals(AccountWriteResult.InvalidUrl, repo.add("Home", "ftp://nope", "Kyle", "p"))
        assertEquals(emptyList<Account>(), repo.observe().first())

        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        assertEquals(
            AccountWriteResult.InvalidUrl,
            repo.updateCredentials(id, "   ", "Kyle", password = null),
        )
        assertEquals("http://h:4533", repo.observe().first().single().baseUrl)
    }

    // ---- I9: a failed seal is visible to the caller ----

    @Test fun `add reports SecretUnavailable when the device cannot seal, and keeps the row`() = runTest {
        // Nulled BEFORE add, not after: the existing lost-key test only nulls the key once add
        // has already succeeded, so this path had never run. The caller here has just probed
        // this exact password successfully against the real server — reporting a credential
        // error would send the user into a retry that fails identically, forever.
        key = null
        val result = repo.add("Home", "http://h:4533", "Kyle", "hunter2")
        val unavailable = result as? AccountWriteResult.SecretUnavailable
            ?: error("expected SecretUnavailable, got $result")

        // The resting state: the row exists, hasSecret is false, so the screen can offer to
        // take the password again rather than losing the account.
        assertEquals(
            listOf(unavailable.sourceId to false),
            repo.observe().first().map { it.sourceId to it.hasSecret },
        )
        assertNull("a secret file was written despite the seal failing", files.read(unavailable.sourceId))
    }

    @Test fun `a password change that cannot be sealed does not leave the OLD secret behind`() = runTest {
        val id = repo.addOk("Home", "http://h:4533", "Kyle", "hunter2")
        key = null
        val result = repo.updateCredentials(id, "http://h:4533", "Kyle", password = "newpass")
        assertEquals(AccountWriteResult.SecretUnavailable(id), result)
        // The sharp version of I9: the row says "newpass was set" while the file still holds
        // "hunter2". Every request would then go out with a password the user has replaced, and
        // hasSecret would read true, so nothing on screen would ever say so.
        assertNull("the superseded secret file survived a failed password change", files.read(id))
    }

    @Test fun `editing an account that no longer exists says so instead of succeeding quietly`() = runTest {
        assertEquals(
            AccountWriteResult.NoSuchAccount,
            repo.updateCredentials("ghost", "http://h:4533", "Kyle", password = "p"),
        )
    }

    private fun sourceWithId(sourceId: String) = object : LibrarySource {
        override val id = sourceId
        override suspend fun listSongs() = emptyList<Song>()
        override suspend fun listAlbums() = emptyList<Album>()
        override suspend fun listArtists() = emptyList<Artist>()
        override suspend fun search(query: String) = emptyList<Song>()
        override fun resolvePlayableUri(song: Song) = song.uri
        override fun stableKey(song: Song) = song.uri
    }
}
