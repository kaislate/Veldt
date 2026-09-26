// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import com.kaislate.veldtplayer.data.net.SubsonicClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

/**
 * [ScrobbleFlusher] (design spec §5) end to end: real Room, a real [SubsonicClient] against
 * [FakeHttpServer], a real [SubsonicSources]/[AccountRepository] pair, and a real, temp-dir-backed
 * [ScrobbleQueue] — only the account row and the server's canned answers are fixtures. Mirrors
 * `SubsonicSyncWorkerTest`'s setup for exactly the same reasons (see [NoOpDispatcher]'s KDoc).
 *
 * [sources] is built PER TEST, after the account row it needs already exists — not once in a
 * shared `@Before` — because [NoOpDispatcher] means [SubsonicSources]' `accountDao.observeAll()`
 * collector never actually runs, so `sources`/`rows` are frozen at whatever
 * [SubsonicSources]' own construction-time synchronous read saw. [ScrobbleFlusher.flush] calls
 * [SubsonicSources.contains], which (unlike [SubsonicSources.credentials]) reads that frozen
 * snapshot, not a fresh query — so a [SubsonicSources] built before the account row exists would
 * report every account "unknown" forever, exactly the failure mode `SubsonicSyncWorkerTest`'s own
 * KDoc warns never to introduce a live scope to work around.
 *
 * This file's control (see the task report): letting [ScrobbleFlusher.flush] continue past an
 * [com.kaislate.veldtplayer.data.net.ScrobbleResult.Unreachable] result instead of stopping
 * reddens "flush delivers oldest first and stops at the first unreachable result" below, by
 * sending a third request the correct implementation never makes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrobbleFlusherTest {

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var songDao: SongDao
    private lateinit var accounts: AccountRepository
    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient
    private lateinit var queueDir: File
    private lateinit var queue: ScrobbleQueue
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** See the class KDoc for why [SubsonicSources] must not be handed a live collector scope. */
    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Deliberately does nothing — see the class KDoc.
        }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java).allowMainThreadQueries().build()
        accountDao = db.accountDao()
        songDao = db.songDao()
        accounts = AccountRepository(
            dao = accountDao,
            box = SecretBox(object : KeyProvider {
                override fun secretKey(): SecretKey? = key
            }),
            files = SecretFiles(context),
        )
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
        queueDir = Files.createTempDirectory("scrobble-flusher-test").toFile()
        queue = ScrobbleQueue(queueDir)
    }

    @After fun tearDown() {
        server.close()
        db.close()
        queueDir.deleteRecursively()
    }

    private suspend fun addAccount(
        baseUrl: String = server.baseUrl,
        username: String = "kyle",
        password: String = "hunter2",
    ): String {
        val result = accounts.add("Home", baseUrl, username, password)
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    /** Built AFTER the account row exists — see the class KDoc. */
    private fun sources(): SubsonicSources =
        SubsonicSources(accountDao, songDao, accounts, CoroutineScope(SupervisorJob() + NoOpDispatcher))

    private fun flusher(sources: SubsonicSources = sources()) = ScrobbleFlusher(queue, client, sources)

    private fun okEnvelope() = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

    private fun failedEnvelope(code: Int, message: String) =
        """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":$code,"message":"$message"}}}"""

    // ------------------------------------------------------------------------------- order / stop

    @Test fun `flush delivers oldest first and stops at the first unreachable result`() = runTest {
        val sourceId = addAccount()
        val e1 = QueuedScrobble(sourceId, "song-1", 1_000L)
        val e2 = QueuedScrobble(sourceId, "song-2", 2_000L)
        val e3 = QueuedScrobble(sourceId, "song-3", 3_000L)
        listOf(e1, e2, e3).forEach(queue::add)
        server.enqueue(okEnvelope()) // e1: delivered
        server.enqueue("this is not a subsonic envelope at all") // e2: Malformed -> Unreachable

        flusher().flush(sourceId)

        assertEquals(
            "expected exactly 2 requests: e1 delivered, e2's unreachable answer must stop the loop before e3",
            2,
            server.requests.size,
        )
        assertEquals("e1 delivered; e2 and e3 remain, in order", listOf(e2, e3), queue.forSource(sourceId))
    }

    @Test fun `an ok result removes the entry and the request carries submission=true and time`() = runTest {
        val sourceId = addAccount()
        val e1 = QueuedScrobble(sourceId, "song-1", 1_700_000_000_000L)
        queue.add(e1)
        server.enqueue(okEnvelope())

        flusher().flush(sourceId)

        assertEquals(emptyList<QueuedScrobble>(), queue.forSource(sourceId))
        assertEquals("song-1", server.queryParam(0, "id"))
        assertEquals("true", server.queryParam(0, "submission"))
        assertEquals("1700000000000", server.queryParam(0, "time"))
    }

    // ---------------------------------------------------------------------------- credential rejection

    @Test fun `a credential rejection (40) auth-blocks the source and stops without dropping the entry`() = runTest {
        val sourceId = addAccount()
        val e1 = QueuedScrobble(sourceId, "song-1", 1_000L)
        queue.add(e1)
        server.enqueue(failedEnvelope(40, "Wrong username or password"))

        flusher().flush(sourceId)

        assertTrue("40 means credentials won't work: the source must be auth-blocked", queue.isAuthBlocked(sourceId))
        assertEquals("the rejected entry must stay queued, not be dropped", listOf(e1), queue.forSource(sourceId))
    }

    @Test fun `an auth-blocked source is skipped with no request at all`() = runTest {
        val sourceId = addAccount()
        val e1 = QueuedScrobble(sourceId, "song-1", 1_000L)
        queue.add(e1)
        queue.setAuthBlocked(sourceId, true)

        flusher().flush(sourceId)

        assertEquals("an auth-blocked source must never be contacted", emptyList<FakeHttpServer.Recorded>(), server.requests)
        assertEquals(listOf(e1), queue.forSource(sourceId))
    }

    // -------------------------------------------------------------------------- non-credential rejection

    @Test fun `a non-credential rejection (70) drops only that entry and continues to the next`() = runTest {
        val sourceId = addAccount()
        val e1 = QueuedScrobble(sourceId, "song-1", 1_000L)
        val e2 = QueuedScrobble(sourceId, "song-2", 2_000L)
        queue.add(e1)
        queue.add(e2)
        server.enqueue(failedEnvelope(70, "not found"))
        server.enqueue(okEnvelope())

        flusher().flush(sourceId)

        assertEquals(2, server.requests.size)
        assertEquals(emptyList<QueuedScrobble>(), queue.forSource(sourceId))
        assertFalse("a 70 is not a credential problem", queue.isAuthBlocked(sourceId))
    }

    // ------------------------------------------------------------------------------------ unknown source

    @Test fun `an unknown source's entries are purged with no request`() = runTest {
        queue.add(QueuedScrobble("ghost", "song-1", 1_000L))

        flusher().flush("ghost")

        assertEquals(emptyList<FakeHttpServer.Recorded>(), server.requests)
        assertEquals(emptyList<QueuedScrobble>(), queue.forSource("ghost"))
    }

    // ------------------------------------------------------------------------------------------- flushAll

    @Test fun `flushAll flushes every source with entries`() = runTest {
        val a = addAccount(username = "kyle-a")
        val other = FakeHttpServer().also { it.start() }
        try {
            val b = accounts.add("Other", other.baseUrl, "kyle-b", "hunter2").let {
                (it as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $it")
            }
            queue.add(QueuedScrobble(a, "song-a", 1_000L))
            queue.add(QueuedScrobble(b, "song-b", 2_000L))
            server.enqueue(okEnvelope())
            other.enqueue(okEnvelope())

            flusher().flushAll()

            assertEquals(emptyList<QueuedScrobble>(), queue.forSource(a))
            assertEquals(emptyList<QueuedScrobble>(), queue.forSource(b))
        } finally {
            other.close()
        }
    }
}
