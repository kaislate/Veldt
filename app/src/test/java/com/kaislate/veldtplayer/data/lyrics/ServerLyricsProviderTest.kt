// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

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
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

/**
 * Robolectric: [VeldtUri.parse] uses `android.net.Uri`, and building a real [SubsonicSources]
 * needs a real Room database (same setup `SubsonicSourcesTest` uses, copied here rather than
 * shared — see that file's KDoc for why).
 *
 * Negative control this file is designed to catch (see the task report): removing the
 * `caps.supports("songLyrics")` gate reddens the "capability absent" test below by letting a
 * request reach [FakeHttpServer].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerLyricsProviderTest {

    private lateinit var ctx: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var accounts: AccountRepository
    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** No live account-change collector needed: no test here mutates accounts after construction. */
    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {}
    }

    private fun inertScope(): CoroutineScope = CoroutineScope(SupervisorJob() + NoOpDispatcher)

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Executor { it.run() })
            .build()
        accountDao = db.accountDao()
        accounts = AccountRepository(
            dao = accountDao,
            box = SecretBox(object : KeyProvider {
                override fun secretKey(): SecretKey? = key
            }),
            files = SecretFiles(ctx),
            newId = { java.util.UUID.randomUUID().toString() },
        )
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(
            http = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
            random = Random(42),
        )
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    private fun sources() = SubsonicSources(accountDao, db.songDao(), accounts, inertScope())

    private fun song(uri: String, filePath: String? = null) = Song(
        id = 1L,
        sourceId = "irrelevant",
        externalId = "1",
        uri = uri,
        filePath = filePath,
        relativeKey = null,
        title = "t",
        artist = "a",
        album = "al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 0L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    private fun accountEntity(sourceId: String, capabilities: String? = null) = AccountEntity(
        sourceId = sourceId,
        displayName = "D",
        baseUrl = "http://unused",
        username = "kyle",
        authMode = AccountEntity.AUTH_TOKEN,
        capabilities = capabilities,
        createdAtMs = 1L,
    )

    @Test fun `a non-remote uri is null and makes no request`() = runTest {
        val provider = ServerLyricsProvider(client, sources())
        val result = provider.lyricsFor(song(uri = "content://media/external/audio/media/1"))
        assertNull(result)
        assertEquals(emptyList<FakeHttpServer.Recorded>(), server.requests)
    }

    @Test fun `a capability-absent server is null with zero requests`() = runTest {
        // A REAL account with real, resolvable credentials — capabilities left uncached
        // (BASELINE, no "songLyrics") is the only thing standing between this call and an
        // actual request to `server`. That is exactly what makes this test able to catch a
        // dropped gate: an account that also lacked credentials would answer null (and record
        // no request) for the wrong reason, passing even with the gate deleted.
        val added = accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        val sourceId = (added as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $added")
        val provider = ServerLyricsProvider(client, sources())
        val result = provider.lyricsFor(song(uri = VeldtUri.track(sourceId, "song-1")))
        assertNull(result)
        assertTrue("expected zero requests, got ${server.requests}", server.requests.isEmpty())
    }

    @Test fun `missing credentials is null`() = runTest {
        // Capability cached, but no sealed secret was ever written for this row (bypassing
        // AccountRepository.add) -> credentials() resolves to null.
        accountDao.upsert(accountEntity("acct-1", capabilities = "songLyrics"))
        val provider = ServerLyricsProvider(client, sources())
        val result = provider.lyricsFor(song(uri = VeldtUri.track("acct-1", "song-1")))
        assertNull(result)
    }

    @Test fun `a supported server with credentials returns the mapped lyrics`() = runTest {
        val added = accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        val sourceId = (added as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $added")
        accounts.cacheCapabilities(sourceId, listOf("songLyrics"))
        server.enqueue(
            """{"subsonic-response":{"status":"ok","version":"1.16.1","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":false,"line":[{"value":"la la"}]}]}}}""",
        )

        val provider = ServerLyricsProvider(client, sources())
        val result = provider.lyricsFor(song(uri = VeldtUri.track(sourceId, "song-1")))

        assertEquals(Lyrics.Plain("la la"), result)
        assertEquals("song-1", server.queryParam(0, "id"))
    }
}
