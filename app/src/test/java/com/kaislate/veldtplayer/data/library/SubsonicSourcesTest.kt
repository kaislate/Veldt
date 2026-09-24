// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.account.db.AccountEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.net.ServerCapabilities
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

/**
 * Accounts as sources, at runtime (N2 Task 2 — spec §4.2, §5.2).
 *
 * Robolectric, in-memory Room, and a fake [KeyProvider] — the same setup `AccountRepositoryTest`
 * uses, copied here rather than shared, because [SecretBox]'s seam exists precisely so each test
 * class can control its own key without touching the real Android Keystore.
 *
 * **Timing.** [SubsonicSources]' construction-time snapshot is read synchronously (the very first
 * test below pins that). The [AccountDao.observeAll] collector that keeps it current afterward is
 * a separate coroutine on whatever [CoroutineScope] is handed in; tests that need it to actually
 * make progress use [collectorScope] — a real [Dispatchers.IO] scope, matching production — rather
 * than `runTest`'s own `backgroundScope`. `backgroundScope` shares `runTest`'s
 * `StandardTestDispatcher`, which only runs queued work when the test body itself suspends back
 * onto that same dispatcher; a test that polls with a blocking `Thread.sleep` (needed here because
 * Room's invalidation notification is genuinely asynchronous, on Room's own query executor) never
 * yields control back to it, so the collector would simply never run. [awaitTrue] polls for the
 * eventually-consistent result rather than assuming any particular ordering.
 *
 * **Teardown must not race [db].close().** Room's DEFAULT (unset) query executor is
 * `ArchTaskExecutor`'s IO pool — a PROCESS-WIDE singleton shared by every Room database in the
 * whole test JVM, not something scoped to this test or to the coroutine that launched
 * `observeAll()`'s collector. `TriggerBasedInvalidationTracker`'s own housekeeping (triggered by
 * a write, e.g. `accountDao.upsert`) is dispatched onto that shared executor independently of
 * the collecting coroutine's structured concurrency, so — measured directly — `scope.cancel()`
 * alone does not stop an already-queued task, and even `cancelAndJoin` on the collector's own
 * `Job` does not, because `join` only waits for OUR coroutine's structured descendants, and this
 * housekeeping is not one. A task queued for THIS test's database can sit behind unrelated work
 * from every other test sharing that one process-wide pool and run only after [tearDown]'s
 * `db.close()` — surfacing as an UNCAUGHT exception blamed on whichever unrelated test happens
 * to be running when it finally fires. This is exactly why N2 Task 6 adding ~16 more tests
 * elsewhere in the suite (deepening that shared queue) turned a latent race into one that
 * reproduced on 7+ unrelated classes, every run: the failure was never really about this file's
 * OWN test count.
 *
 * [setUp] fixes this at its actual root: `Room.Builder.setQueryExecutor` is given a same-thread
 * executor, so every query this database ever runs — the ordinary ones AND Room's own
 * invalidation housekeeping — executes synchronously, in-line, on whatever thread asks for it.
 * There is no background task left to queue, so there is nothing for `db.close()` to race.
 * [collectorScope] is still `cancelAndJoin`ed in [tearDown] first, as good hygiene for OUR own
 * launched coroutine, but it is the synchronous executor — not the join — that makes closing
 * safe. Every test that does not need the collector to run at all uses [inertScope] instead: a
 * scope whose dispatcher never runs anything handed to it, so `observeAll()` is never even
 * invoked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicSourcesTest {

    private lateinit var ctx: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var files: SecretFiles
    private lateinit var accounts: AccountRepository
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val collectorScopes = mutableListOf<CoroutineScope>()

    private fun box(onKeyAccess: () -> Unit = {}) = SecretBox(object : KeyProvider {
        override fun secretKey(): SecretKey? {
            onKeyAccess()
            return key
        }
    })

    /** A real background scope, matching what the `@Inject` constructor builds in production —
     *  see the class KDoc for why `backgroundScope` cannot stand in for it here. `cancelAndJoin`ed
     *  in [tearDown]. */
    private fun collectorScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { collectorScopes += it }

    /**
     * For every test that does NOT need the collector to run — see the class KDoc. Overriding
     * [CoroutineDispatcher.dispatch] to swallow every `Runnable` means `init { scope.launch {
     * accountDao.observeAll().collect { … } } }`'s body never executes at all: `CoroutineStart
     * .DEFAULT` resumes the new coroutine through this dispatcher, and a dispatcher that never
     * runs what it is given leaves nothing to race against [tearDown]'s `db.close()`.
     */
    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Deliberately does nothing — see the class KDoc.
        }
    }

    private fun inertScope(): CoroutineScope = CoroutineScope(SupervisorJob() + NoOpDispatcher)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java)
            .allowMainThreadQueries()
            // A same-thread executor, not a background pool: Room's DEFAULT (unset) query
            // executor is `ArchTaskExecutor`'s IO pool, a PROCESS-WIDE singleton shared by every
            // Room database in the whole test JVM, whose queue depth (and therefore how long a
            // task can sit queued before it runs) is a function of TOTAL SUITE LOAD — which is
            // exactly why this file's `observeAll()` collector tests started failing only once
            // N2 Task 6 added enough unrelated tests elsewhere to grow that queue. A `Flow`'s
            // invalidation housekeeping (Room's `TriggerBasedInvalidationTracker`) dispatches
            // onto that executor independently of this test's own coroutine scope, so no amount
            // of `cancelAndJoin` on OUR launched collector can guarantee it has already run
            // before `tearDown`'s `db.close()` — measured: it does not. Running every query
            // synchronously, in-line, on whatever thread asks for it removes the asynchrony (and
            // therefore the race) entirely, rather than trying to out-wait it.
            .setQueryExecutor(Executor { it.run() })
            .build()
        accountDao = db.accountDao()
        files = SecretFiles(ctx)
        accounts = AccountRepository(
            dao = accountDao,
            box = box(),
            files = files,
            newId = { java.util.UUID.randomUUID().toString() },
        )
    }

    @After fun tearDown() {
        // join, not just cancel — see the class KDoc's "Teardown must not race db.close()". The
        // synchronous query executor (see [setUp]) is what makes this actually sufficient: there
        // is no background task left in flight for `join` to race against.
        runBlocking { collectorScopes.forEach { it.coroutineContext[Job]?.cancelAndJoin() } }
        db.close()
    }

    private fun entity(
        sourceId: String,
        baseUrl: String = "http://h",
        capabilities: String? = null,
    ) = AccountEntity(
        sourceId = sourceId,
        displayName = "D",
        baseUrl = baseUrl,
        username = "kyle",
        authMode = AccountEntity.AUTH_TOKEN,
        capabilities = capabilities,
        createdAtMs = 1L,
    )

    /** Bounded, real-time polling for an eventually-consistent background collector. See the
     *  class KDoc for why this is not `advanceUntilIdle()`. */
    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("condition was not observed within ${timeoutMs}ms")
            }
            Thread.sleep(5)
        }
    }

    // ---- construction is synchronous ---------------------------------------------------------

    @Test fun `an account that exists at construction is a source immediately, with no collection needed`() =
        runTest {
            accountDao.upsert(entity("acct-1"))
            val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())
            // Synchronous on purpose: PlaylistRepository.resolve and MusicRepository.playableUri
            // call byId without suspending, and a cold start must not render every remote entry
            // unresolved.
            assertEquals("acct-1", sources.byId("acct-1")?.id)
        }

    // ---- the runtime collector ----------------------------------------------------------------

    @Test fun `an account added later becomes a source, and a removed one stops being one`() = runTest {
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, collectorScope())
        assertNull("acct-1 was never written yet", sources.byId("acct-1"))

        accountDao.upsert(entity("acct-1"))
        awaitTrue { sources.byId("acct-1") != null }
        assertEquals("acct-1", sources.byId("acct-1")?.id)

        accountDao.delete("acct-1")
        awaitTrue { sources.byId("acct-1") == null }
    }

    @Test fun `credentials are cached and dropped when the account row changes`() = runTest {
        var decryptCount = 0
        val countingAccounts = AccountRepository(
            dao = accountDao,
            box = box(onKeyAccess = { decryptCount++ }),
            files = files,
            newId = { "acct-1" },
        )
        countingAccounts.add("Home", "http://h1", "kyle", "hunter2")
        val sources = SubsonicSources(accountDao, db.songDao(), countingAccounts, collectorScope())

        decryptCount = 0
        val first = sources.credentials("acct-1")
        val second = sources.credentials("acct-1")
        assertEquals("a cache hit must not read back a different value", first, second)
        assertEquals("two credentials() calls must decrypt exactly once", 1, decryptCount)

        countingAccounts.updateCredentials("acct-1", "http://h2", "kyle", password = null)
        decryptCount = 0
        // Poll credentials() itself: a call that still hits the (stale) cache keeps returning the
        // OLD baseUrl and decrypting nothing, so this only returns once the collector has evicted
        // the entry and the next call falls through to a fresh read.
        val deadline = System.currentTimeMillis() + 2_000
        var third = sources.credentials("acct-1")
        while (third?.baseUrl != "http://h2") {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("the changed row was never reflected; last=$third")
            }
            Thread.sleep(5)
            third = sources.credentials("acct-1")
        }
        assertEquals("http://h2", third.baseUrl)
        assertEquals("the changed row must decrypt again rather than reuse the stale cache", 1, decryptCount)
    }

    @Test fun `a password changed without any row change is picked up on the very next call`() = runTest {
        // The exact device bug (Task 7a): AccountRepository.updateCredentials with only a new
        // password rewrites the sealed FILE while upserting an identical row, so the collector's
        // row-equality eviction (above) never fires. The cache must still notice.
        var decryptCount = 0
        val countingAccounts = AccountRepository(
            dao = accountDao,
            box = box(onKeyAccess = { decryptCount++ }),
            files = files,
            newId = { "acct-1" },
        )
        countingAccounts.add("Home", "http://h1", "kyle", "old")
        // inertScope(): no row ever changes in this test, so the live collector is not needed —
        // see the class KDoc on tests that don't need it not starting one against Room.
        val sources = SubsonicSources(accountDao, db.songDao(), countingAccounts, inertScope())

        decryptCount = 0
        val first = sources.credentials("acct-1")
        assertEquals("old", first?.password)
        assertEquals("the first call must decrypt exactly once", 1, decryptCount)

        countingAccounts.updateCredentials("acct-1", "http://h1", "kyle", password = "new")

        decryptCount = 0
        val second = sources.credentials("acct-1")
        assertEquals(
            "the new password must be picked up on the very next call, not after the process dies",
            "new",
            second?.password,
        )
        assertEquals("a changed sealed secret must decrypt again", 1, decryptCount)

        decryptCount = 0
        val third = sources.credentials("acct-1")
        assertEquals("new", third?.password)
        assertEquals("an unchanged secret must not be decrypted a third time", 0, decryptCount)
    }

    @Test fun `an unchanged secret is not decrypted again`() = runTest {
        var decryptCount = 0
        val countingAccounts = AccountRepository(
            dao = accountDao,
            box = box(onKeyAccess = { decryptCount++ }),
            files = files,
            newId = { "acct-1" },
        )
        countingAccounts.add("Home", "http://h1", "kyle", "hunter2")
        val sources = SubsonicSources(accountDao, db.songDao(), countingAccounts, inertScope())

        decryptCount = 0
        val first = sources.credentials("acct-1")
        val second = sources.credentials("acct-1")
        assertEquals("hunter2", first?.password)
        assertEquals("a cache hit must not read back a different value", first, second)
        assertEquals("two credentials() calls must decrypt exactly once", 1, decryptCount)
    }

    @Test fun `a deleted secret file evicts the cache`() = runTest {
        accounts.add("Home", "http://h1", "kyle", "hunter2")
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())

        val first = sources.credentials("acct-none-yet")
        assertNull("sanity: unrelated id resolves to null", first)

        val sourceId = accountDao.getAll().single().sourceId
        assertEquals("hunter2", sources.credentials(sourceId)?.password)

        files.delete(sourceId)
        assertNull(
            "a deleted secret file must evict the cache, not keep serving the old password",
            sources.credentials(sourceId),
        )
    }

    @Test fun `credentials for an account whose secret cannot be read are null`() = runTest {
        // Written directly, bypassing AccountRepository.add — so no secret file exists at all.
        accountDao.upsert(entity("acct-1"))
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())
        assertNull(sources.credentials("acct-1"))
    }

    @Test fun `credentials resolve for a row the collector has not seen yet`() = runTest {
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())
        // The exact race the sync worker hits: the row lands, and something asks for credentials
        // before observeAll()'s collector — a SEPARATE coroutine — has had any chance to run.
        // Deliberately no advanceUntilIdle() and no awaitTrue() before the call below.
        val result = accounts.add("Home", "http://fresh", "kyle", "hunter2")
        val sourceId = (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")

        val creds = sources.credentials(sourceId)
        assertEquals("http://fresh", creds?.baseUrl)
        assertEquals("kyle", creds?.username)
        assertEquals("hunter2", creds?.password)
    }

    @Test fun `an account id that would break the uri encodings is refused`() = runTest {
        // UUIDs never contain ':' or '/', but the row is external data — a future migration or an
        // out-of-band edit could still produce one.
        accountDao.upsert(entity("good-1"))
        accountDao.upsert(entity("a/b"))
        accountDao.upsert(entity("a:b"))
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())

        assertNull(sources.byId("a/b"))
        assertNull(sources.byId("a:b"))
        // Refused, not crashed — and the other, legal accounts are unaffected.
        assertEquals("good-1", sources.byId("good-1")?.id)
        assertEquals(setOf("good-1"), sources.all.map { it.id }.toSet())
    }

    // ---- capabilities ---------------------------------------------------------------------------

    @Test fun `capabilities are the cached extension names, or BASELINE when none are cached`() = runTest {
        accountDao.upsert(entity("acct-1", capabilities = "transcodeOffset,songLyrics"))
        accountDao.upsert(entity("acct-2", capabilities = null))
        val sources = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())

        assertEquals(
            ServerCapabilities(mapOf("transcodeOffset" to emptyList(), "songLyrics" to emptyList())),
            sources.capabilities("acct-1"),
        )
        assertEquals(ServerCapabilities.BASELINE, sources.capabilities("acct-2"))
        assertEquals(ServerCapabilities.BASELINE, sources.capabilities("no-such-account"))
    }
}
