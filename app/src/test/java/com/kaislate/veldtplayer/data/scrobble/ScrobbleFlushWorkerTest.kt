// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.library.SubsonicSources
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
 * [ScrobbleFlushWorker] (design spec §5). Built via [TestListenableWorkerBuilder] with a
 * [WorkerFactory] that calls the worker's own (non-Hilt) constructor directly, exactly as
 * `SubsonicSyncWorkerTest` does — [flusher] is real, driven against a real [FakeHttpServer], so
 * "the worker calls [ScrobbleFlusher.flushAll]" is proven by an observable effect (the queue
 * actually draining), not by a mock verifying it was called.
 *
 * [NoOpDispatcher] and building [SubsonicSources] only after the account row exists: see
 * `ScrobbleFlusherTest`'s KDoc — the same reasoning applies here unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrobbleFlushWorkerTest {

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient
    private lateinit var queueDir: File
    private lateinit var queue: ScrobbleQueue
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Deliberately does nothing — see ScrobbleFlusherTest's KDoc.
        }
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java).allowMainThreadQueries().build()
        accounts = AccountRepository(
            dao = db.accountDao(),
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
        queueDir = Files.createTempDirectory("scrobble-flush-worker-test").toFile()
        queue = ScrobbleQueue(queueDir)
    }

    @After fun tearDown() {
        server.close()
        db.close()
        queueDir.deleteRecursively()
    }

    private suspend fun addAccount(): String {
        val result = accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    private fun flusher(): ScrobbleFlusher {
        val sources = SubsonicSources(db.accountDao(), db.songDao(), accounts, CoroutineScope(SupervisorJob() + NoOpDispatcher))
        return ScrobbleFlusher(queue, client, sources)
    }

    private fun worker(flusher: ScrobbleFlusher): ScrobbleFlushWorker =
        TestListenableWorkerBuilder.from(context, ScrobbleFlushWorker::class.java)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ScrobbleFlushWorker(appContext, workerParameters, flusher)
            })
            .build()

    private fun okEnvelope() = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

    @Test fun `the worker flushes the queue and returns success`() = runTest {
        val sourceId = addAccount()
        queue.add(QueuedScrobble(sourceId, "song-1", 1_000L))
        server.enqueue(okEnvelope())

        val result = worker(flusher()).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(emptyList<QueuedScrobble>(), queue.forSource(sourceId))
    }

    /** Plan Global Constraint 5: the flush worker never retries itself. An entry left behind by
     *  an unreachable answer must still yield [ListenableWorker.Result.success] — never `.retry`
     *  — so nothing here fights WorkManager's own backoff; the next piggyback or a later failure
     *  is what re-arms delivery, per design spec §5. */
    @Test fun `the worker still returns success when an entry is left behind unreachable`() = runTest {
        val sourceId = addAccount()
        queue.add(QueuedScrobble(sourceId, "song-1", 1_000L))
        server.enqueue("this is not a subsonic envelope at all")

        val result = worker(flusher()).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, queue.forSource(sourceId).size)
    }
}
