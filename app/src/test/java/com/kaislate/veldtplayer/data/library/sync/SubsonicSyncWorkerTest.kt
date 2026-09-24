// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.account.db.AccountEntity
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import com.kaislate.veldtplayer.data.net.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The worker end to end: real Room, a real [SubsonicClient] against [FakeHttpServer], and a real
 * [SubsonicSources]/[AccountRepository] pair — only the account row and the server's canned
 * answers are fixtures. Built via [TestListenableWorkerBuilder] with a [WorkerFactory] that calls
 * the worker's own (non-Hilt) constructor directly, exactly as `LibraryScanWorkerTest` does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicSyncWorkerTest {

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var songDao: SongDao
    private lateinit var accounts: AccountRepository
    private lateinit var sources: SubsonicSources
    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient
    private lateinit var status: SyncStatusStore
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val scopes = mutableListOf<CoroutineScope>()

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
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        sources = SubsonicSources(accountDao, songDao, accounts, scope)
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
        status = SyncStatusStore(context)
        runBlocking { status.clearForTest() }
    }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        server.close()
        db.close()
    }

    // ------------------------------------------------------------------------------- fixtures

    private suspend fun addAccount(
        baseUrl: String = server.baseUrl,
        username: String = "kyle",
        password: String = "hunter2",
    ): String {
        val result = accounts.add("Home", baseUrl, username, password)
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    private fun accountEntity(sourceId: String) = AccountEntity(
        sourceId = sourceId, displayName = "D", baseUrl = server.baseUrl, username = "kyle",
        authMode = AccountEntity.AUTH_TOKEN, capabilities = null, createdAtMs = 1L,
    )

    private fun songEntity(sourceId: String, externalId: String) = SongEntity(
        id = 0L, sourceId = sourceId, externalId = externalId, uri = "content://$externalId",
        filePath = null, relativeKey = null, title = "t", artist = "A", album = "Al",
        albumArtist = null, trackNumber = null, discNumber = null, year = null,
        durationMs = 1_000L, dateModifiedSec = 100L, hasEmbeddedArt = false,
    )

    private fun extensionsJson(names: List<String>) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","openSubsonicExtensions":[${
            names.joinToString(",") { """{"name":"$it","versions":[1]}""" }
        }]}}"""

    private fun albumListJson(ids: List<String>) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","albumList2":{"album":[${
            ids.joinToString(",") { """{"id":"$it"}""" }
        }]}}}"""

    private fun albumJson(id: String, songIds: List<String>) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"$id","song":[${
            songIds.joinToString(",") { """{"id":"$it","title":"$it"}""" }
        }]}}}"""

    /** Serves `getOpenSubsonicExtensions`, `getAlbumList2` (one page) and `getAlbum`, keyed off
     *  the request target regardless of GET/POST placement. */
    private fun serveCatalog(songsByAlbum: Map<String, List<String>>, extensions: List<String> = emptyList()) {
        server.respond { recorded ->
            val target = recorded.target
            when {
                "getOpenSubsonicExtensions" in target ->
                    FakeHttpServer.Canned(200, extensionsJson(extensions).toByteArray(), "application/json")
                "getAlbumList2" in target -> {
                    val offset = Regex("offset=(\\d+)").find(target)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val ids = if (offset == 0) songsByAlbum.keys.toList() else emptyList()
                    FakeHttpServer.Canned(200, albumListJson(ids).toByteArray(), "application/json")
                }
                "getAlbum" in target -> {
                    val id = Regex("id=([^&]+)").find(target)?.groupValues?.get(1) ?: ""
                    FakeHttpServer.Canned(200, albumJson(id, songsByAlbum[id].orEmpty()).toByteArray(), "application/json")
                }
                else -> null
            }
        }
    }

    /** Answers `getAlbumList2` with a Subsonic error code, e.g. 40 (wrong credentials). Every
     *  other endpoint (including `getOpenSubsonicExtensions`, called first) falls through to the
     *  default 500 the server hands back with nothing queued, which `capabilities()` treats as
     *  BASELINE rather than propagating. */
    private fun serveAlbumListError(code: Int, message: String) {
        server.respond { recorded ->
            if ("getAlbumList2" in recorded.target) {
                FakeHttpServer.Canned(
                    200,
                    """{"subsonic-response":{"status":"failed","version":"1.16.1",
                        "error":{"code":$code,"message":"$message"}}}""".toByteArray(),
                    "application/json",
                )
            } else {
                null
            }
        }
    }

    private fun worker(
        sourceId: String,
        runAttemptCount: Int = 0,
        now: () -> Long = { 1_000L },
    ): SubsonicSyncWorker =
        TestListenableWorkerBuilder.from(context, SubsonicSyncWorker::class.java)
            .setInputData(workDataOf(SubsonicSyncWorker.KEY_SOURCE_ID to sourceId))
            .setRunAttemptCount(runAttemptCount)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SubsonicSyncWorker(
                    appContext, workerParameters, client, sources, accounts, accountDao, songDao, status, now,
                )
            })
            .build()

    // ---------------------------------------------------------------------------------- success

    @Test fun `success lands the server's songs under the account and records status`() = runTest {
        val sourceId = addAccount()
        serveCatalog(mapOf("al1" to listOf("s1", "s2")))

        val result = worker(sourceId, now = { 1_000L }).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(setOf("s1", "s2"), songDao.getBySource(sourceId).map { it.externalId }.toSet())
        val recorded = status.status(sourceId).first()
        assertEquals(1_000L, recorded.lastSuccessMs)
        assertEquals(2, recorded.songCount)
    }

    @Test fun `capabilities are cached on the account row after a successful sync`() = runTest {
        val sourceId = addAccount()
        serveCatalog(mapOf("al1" to listOf("s1")), extensions = listOf("formPost", "songLyrics"))

        val result = worker(sourceId).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        val cached = accountDao.get(sourceId)?.capabilities.orEmpty().split(",")
        assertTrue("expected formPost among $cached", "formPost" in cached)
    }

    // ---------------------------------------------------------------------------------- rejection

    @Test fun `a code 40 rejection fails as auth and deletes nothing`() = runTest {
        val sourceId = addAccount()
        songDao.upsertBySourceKey(listOf(songEntity(sourceId, "keep-1")))
        serveAlbumListError(40, "Wrong username or password")

        val result = worker(sourceId).doWork()

        assertEquals(
            ListenableWorker.Result.failure(workDataOf(SubsonicSyncWorker.KEY_FAILURE to "auth")),
            result,
        )
        assertEquals(listOf("keep-1"), songDao.getBySource(sourceId).map { it.externalId })
    }

    // ---------------------------------------------------------------------------------- unreachable/retry

    @Test fun `unreachable retries within budget, then fails without touching stored rows`() = runTest {
        // Nothing listens on this port — the same "dead server" fixture SubsonicClientCatalogTest uses.
        val sourceId = addAccount(baseUrl = "http://127.0.0.1:1")
        songDao.upsertBySourceKey(listOf(songEntity(sourceId, "keep-1")))

        assertEquals(ListenableWorker.Result.retry(), worker(sourceId, runAttemptCount = 0).doWork())
        assertEquals(ListenableWorker.Result.retry(), worker(sourceId, runAttemptCount = 1).doWork())
        assertEquals(
            ListenableWorker.Result.failure(workDataOf(SubsonicSyncWorker.KEY_FAILURE to "unreachable")),
            worker(sourceId, runAttemptCount = 2).doWork(),
        )
        assertEquals(listOf("keep-1"), songDao.getBySource(sourceId).map { it.externalId })
    }

    // ---------------------------------------------------------------------------------- gone / auth (local)

    @Test fun `a missing account fails as gone`() = runTest {
        val result = worker("no-such-account").doWork()
        assertEquals(
            ListenableWorker.Result.failure(workDataOf(SubsonicSyncWorker.KEY_FAILURE to "gone")),
            result,
        )
    }

    @Test fun `credentials that cannot be read fail as auth, without contacting the server`() = runTest {
        // Written directly, bypassing AccountRepository.add — so no secret was ever sealed. This
        // is the Keystore-invalidated-key case in effect, if not in exact cause.
        accountDao.upsert(accountEntity("acct-x"))

        val result = worker("acct-x").doWork()

        assertEquals(
            ListenableWorker.Result.failure(workDataOf(SubsonicSyncWorker.KEY_FAILURE to "auth")),
            result,
        )
        assertEquals("credentials() must fail before any network call", emptyList<Any>(), server.requests)
    }
}
