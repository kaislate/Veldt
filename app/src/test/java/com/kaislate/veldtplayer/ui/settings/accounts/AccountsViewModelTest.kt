// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.sync.SubsonicSync
import com.kaislate.veldtplayer.data.library.sync.SyncStatus
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlushScheduler
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The account-write outcome as the SCREEN reaches it.
 *
 * `AccountRepositoryTest` proves the repository can tell "secure storage is unavailable" from
 * "that address is unusable". This proves the view model ROUTES that distinction to the UI
 * instead of discarding it — a signal the production path never carries is the same defect as no
 * signal at all, and the shipped `add` returned a `String` nobody looked at.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as every other Robolectric suite here is.
@Config(sdk = [34])
class AccountsViewModelTest {

    /**
     * Records what the view model asked of syncing, in ONE list — so an ordering claim (fix
     * round 1: cancel, THEN the account row is deleted, THEN purge) is a single assertion rather
     * than several that could pass separately while the actual interleaving is wrong. Both
     * [cancel] and [purge] record whether the account row was still present in [repo] at the
     * moment they ran: the row must be present for [cancel] and gone for [purge], and only that
     * order proves `AccountsViewModel.delete` sequences `sync.cancel` → `repo.delete` →
     * `sync.purge` rather than merely calling all three in some order.
     */
    private inner class FakeSync : SubsonicSync {
        val calls = mutableListOf<String>()
        override fun request(sourceId: String) {
            calls += "request:$sourceId"
        }
        override fun status(sourceId: String): Flow<SyncStatus> =
            flowOf(SyncStatus(running = false, lastSuccessMs = null, songCount = null, lastError = null))
        override fun cancel(sourceId: String) {
            // cancel() is not suspend (WorkManager.cancelUniqueWork isn't either) — runBlocking
            // only to read repo state for the assertion below, exactly as SubsonicSourcesTest's
            // real-thread patterns do elsewhere in this codebase.
            val stillPresent = runBlocking { repo.observe().first().any { it.sourceId == sourceId } }
            calls += if (stillPresent) "cancel:$sourceId:row-still-present" else "cancel:$sourceId:row-ALREADY-GONE"
        }
        override suspend fun purge(sourceId: String) {
            val stillPresent = repo.observe().first().any { it.sourceId == sourceId }
            calls += if (stillPresent) "purge:$sourceId:row-still-present(BUG)" else "purge:$sourceId:row-gone"
        }
    }

    /** Records every [enqueue] call — N3 task 2's controller ruling: a new password must re-arm
     *  the flush job only when the source actually has entries waiting. */
    private class FakeFlushScheduler : ScrobbleFlushScheduler {
        var enqueueCalls = 0
        override fun enqueue() {
            enqueueCalls++
        }
    }

    private lateinit var db: VeldtDatabase
    private lateinit var repo: AccountRepository
    private lateinit var sync: FakeSync
    private lateinit var scrobbleQueueDir: File
    private lateinit var scrobbleQueue: ScrobbleQueue
    private lateinit var flushScheduler: FakeFlushScheduler
    private lateinit var vm: AccountsViewModel
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java).allowMainThreadQueries().build()
        repo = AccountRepository(
            dao = db.accountDao(),
            box = SecretBox(object : KeyProvider { override fun secretKey(): SecretKey? = key }),
            files = SecretFiles(ctx),
        )
        sync = FakeSync()
        scrobbleQueueDir = Files.createTempDirectory("scrobble-queue-vm-test").toFile()
        scrobbleQueue = ScrobbleQueue(scrobbleQueueDir)
        flushScheduler = FakeFlushScheduler()
        // A real client; no test here makes a request, and a fake would only be a way to be
        // wrong about the constructor.
        vm = AccountsViewModel(repo, SubsonicClient(OkHttpClient(), Random(42)), sync, scrobbleQueue, flushScheduler)
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
        scrobbleQueueDir.deleteRecursively()
    }

    /** The first non-idle save outcome; `add` finishes on Room's executor, not on this thread. */
    private suspend fun settledSave(): SaveState = vm.save.first { it != SaveState.Idle }

    @Test fun `a good save reports Saved and the account is listed`() = runTest {
        vm.add("Home", "192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(SaveState.Saved, settledSave())
        assertEquals(
            listOf("Home" to "http://192.168.50.111:4533"),
            repo.observe().first().map { it.displayName to it.baseUrl },
        )
    }

    @Test fun `a save that cannot store the password does NOT report a credential problem`() = runTest {
        // The user has just tested this exact password against the real server and it worked.
        // Reporting "wrong username or password" here sends them into a retry that fails
        // identically, forever — which is why SaveState.SecretUnavailable is a separate case
        // and not folded into the existing "enter it again" banner.
        key = null
        vm.add("Home", "192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(SaveState.SecretUnavailable, settledSave())

        // The account survives, with hasSecret false, so nothing is silently lost.
        assertEquals(
            listOf("Home" to false),
            repo.observe().first().map { it.displayName to it.hasSecret },
        )
    }

    @Test fun `an unusable address reports InvalidUrl rather than failing silently`() = runTest {
        vm.add("Home", "ftp://nope", "Kyle", "hunter2")
        assertEquals(SaveState.InvalidUrl, settledSave())
        assertEquals(emptyList<String>(), repo.observe().first().map { it.sourceId })
    }

    @Test fun `editing an account that was removed elsewhere reports Gone`() = runTest {
        vm.update("ghost", "192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(SaveState.Gone, settledSave())
    }

    @Test fun `a typed userinfo password never reaches the stored base url`() = runTest {
        // The view model normalises before calling, and the repository normalises again. This
        // asserts the OBSERVABLE result of both, which is the only thing a user is exposed to.
        vm.add("Home", "https://kyle:hunter2@music.example.com", "Kyle", "hunter2")
        assertEquals(SaveState.Saved, settledSave())
        val stored = repo.observe().first().single().baseUrl
        assertEquals("https://music.example.com", stored)
        assertEquals(
            "a credential survived into the stored base url: $stored",
            emptyList<String>(),
            listOf("kyle:", "hunter2@").filter { it in stored },
        )
    }

    @Test fun `editing the url again re-applies the strip`() = runTest {
        vm.add("Home", "http://h:4533", "Kyle", "hunter2")
        assertEquals(SaveState.Saved, settledSave())
        val id = repo.observe().first().single().sourceId

        vm.resetTest()
        vm.update(id, "https://kyle:hunter2@music.example.com", "Kyle", "")
        assertEquals(SaveState.Saved, settledSave())
        assertEquals("https://music.example.com", repo.observe().first().single().baseUrl)
    }

    // ------------------------------------------------------------------------- N2 Task 3: syncing

    @Test fun `adding an account requests a sync for the new id`() = runTest {
        vm.add("Home", "192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(SaveState.Saved, settledSave())
        val sourceId = repo.observe().first().single().sourceId
        assertEquals(listOf("request:$sourceId"), sync.calls)
    }

    @Test fun `a save that could not store the secret requests no sync`() = runTest {
        key = null
        vm.add("Home", "192.168.50.111:4533", "Kyle", "hunter2")
        assertEquals(SaveState.SecretUnavailable, settledSave())
        assertEquals(emptyList<String>(), sync.calls)
    }

    @Test fun `editing the url requests a sync`() = runTest {
        vm.add("Home", "http://h1:4533", "Kyle", "hunter2")
        settledSave()
        val id = repo.observe().first().single().sourceId
        sync.calls.clear()
        vm.resetTest()

        vm.update(id, "http://h2:4533", "Kyle", "")
        assertEquals(SaveState.Saved, settledSave())
        assertEquals(listOf("request:$id"), sync.calls)
    }

    @Test fun `saving with the same url and no new password requests no sync`() = runTest {
        vm.add("Home", "http://h1:4533", "Kyle", "hunter2")
        settledSave()
        val id = repo.observe().first().single().sourceId
        sync.calls.clear()
        vm.resetTest()

        vm.update(id, "http://h1:4533", "Kyle", "")
        assertEquals(SaveState.Saved, settledSave())
        assertEquals(emptyList<String>(), sync.calls)
    }

    // --------------------------------------------------------------- N3 task 2: auth-unblock on a new password

    /**
     * The controller's ruling for N3 task 2: a saved NEW password clears the source's scrobble
     * auth-block and, because this source has entries waiting, re-arms the flush job. Asserted as
     * a TOTAL pair — (still auth-blocked?, enqueue call count) — so a regression that fixes only
     * half of this (unblocks but never flushes, or flushes without ever unblocking) is caught
     * either way.
     */
    @Test fun `saving a new password clears the auth-block and enqueues a flush when entries are waiting`() = runTest {
        vm.add("Home", "http://h1:4533", "Kyle", "hunter2")
        settledSave()
        val id = repo.observe().first().single().sourceId
        scrobbleQueue.setAuthBlocked(id, true)
        scrobbleQueue.add(QueuedScrobble(id, "song-1", 1_000L))
        vm.resetTest()

        vm.update(id, "http://h1:4533", "Kyle", "newpassword")
        assertEquals(SaveState.Saved, settledSave())

        assertEquals(false, scrobbleQueue.isAuthBlocked(id))
        assertEquals(1, flushScheduler.enqueueCalls)
    }

    /** The other half of the same ruling: unblocking must not depend on anything being queued —
     *  an account that was blocked and then emptied by an earlier flush must still be unblocked —
     *  but with nothing waiting there is nothing worth re-arming a job for. */
    @Test fun `saving a new password clears the auth-block but does not enqueue with nothing queued`() = runTest {
        vm.add("Home", "http://h1:4533", "Kyle", "hunter2")
        settledSave()
        val id = repo.observe().first().single().sourceId
        scrobbleQueue.setAuthBlocked(id, true)
        vm.resetTest()

        vm.update(id, "http://h1:4533", "Kyle", "newpassword")
        assertEquals(SaveState.Saved, settledSave())

        assertEquals(false, scrobbleQueue.isAuthBlocked(id))
        assertEquals(0, flushScheduler.enqueueCalls)
    }

    /** Saving with NO new password must touch neither the auth-block nor the flush job — this is
     *  exactly the "credentials change" condition from design spec §5, and a url-only edit is not
     *  one. */
    @Test fun `saving with no new password leaves the auth-block and the flush job alone`() = runTest {
        vm.add("Home", "http://h1:4533", "Kyle", "hunter2")
        settledSave()
        val id = repo.observe().first().single().sourceId
        scrobbleQueue.setAuthBlocked(id, true)
        scrobbleQueue.add(QueuedScrobble(id, "song-1", 1_000L))
        vm.resetTest()

        vm.update(id, "http://h2:4533", "Kyle", "")
        assertEquals(SaveState.Saved, settledSave())

        assertEquals(true, scrobbleQueue.isAuthBlocked(id))
        assertEquals(0, flushScheduler.enqueueCalls)
    }

    /**
     * Fix round 1 (Important finding): `WorkManager.cancelUniqueWork` is cooperative and does not
     * stop a worker already past its network call, so purging the songs/status BEFORE the account
     * row is deleted could race a sync still mid-write and leave its rows orphaned forever (see
     * `SongDao.replaceSourceIfPresent`'s KDoc). The only order that closes that race is cancel →
     * delete the account row → purge, and this asserts exactly that order via what each `FakeSync`
     * call observed about the row's presence at the moment it ran — not merely that all three
     * eventually happened.
     */
    @Test fun `deleting an account cancels, then deletes the row, then purges`() = runTest {
        vm.add("Home", "192.168.50.111:4533", "Kyle", "hunter2")
        settledSave()
        val sourceId = repo.observe().first().single().sourceId
        sync.calls.clear()

        vm.delete(sourceId)
        // delete() launches on viewModelScope and genuinely suspends (repo.observe() and
        // repo.delete() both switch onto Dispatchers.IO), so it has not necessarily finished the
        // moment this call returns. Wait for the whole sequence (both FakeSync calls recorded)
        // rather than asserting immediately.
        awaitCalls(2)

        assertEquals(
            listOf("cancel:$sourceId:row-still-present", "purge:$sourceId:row-gone"),
            sync.calls,
        )
        assertEquals(emptyList<String>(), repo.observe().first().map { it.sourceId })
    }

    /** Real-time polling for `sync.calls` to reach [min] entries — `vm.delete`'s coroutine
     *  genuinely suspends on `Dispatchers.IO` work, same reasoning as `SubsonicSourcesTest
     *  .awaitTrue`. */
    private fun awaitCalls(min: Int, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (sync.calls.size < min) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("expected at least $min calls within ${timeoutMs}ms, got ${sync.calls}")
            }
            Thread.sleep(5)
        }
    }
}
