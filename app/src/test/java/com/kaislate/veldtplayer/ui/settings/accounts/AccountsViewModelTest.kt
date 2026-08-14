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
import com.kaislate.veldtplayer.data.net.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

    private lateinit var db: VeldtDatabase
    private lateinit var repo: AccountRepository
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
        // A real client; no test here makes a request, and a fake would only be a way to be
        // wrong about the constructor.
        vm = AccountsViewModel(repo, SubsonicClient(OkHttpClient(), Random(42)))
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
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
}
