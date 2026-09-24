// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.account.AccountRepository
import com.kaislate.veldtplayer.data.account.AccountWriteResult
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.SecretBox
import com.kaislate.veldtplayer.data.account.SecretFiles
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.net.FakeHttpServer
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.playback.TrackRef
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
import java.io.ByteArrayOutputStream
import java.util.Random
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

/**
 * The production [RemoteArt]: [RemoteArtLoader] over a real [SubsonicClient] talking to
 * [FakeHttpServer], and a real [SubsonicSources] over an in-memory Room database — the same
 * scaffolding `SubsonicSourcesTest` uses, copied here for the same reason that class copies
 * `AccountRepositoryTest`'s: [SecretBox]'s seam exists precisely so each test class controls
 * its own key.
 *
 * Robolectric because decoding the server's bytes back into a [Bitmap] is part of the contract
 * under test, and [AlbumArtFetcherTest] already established that this project's Robolectric
 * config decodes real PNG bytes with the genuine Skia backend rather than a shadow stub — a
 * fixture built from *fake* bytes here would be testing a state the real decoder can never
 * produce.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class RemoteArtLoaderTest {

    private lateinit var ctx: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accounts: AccountRepository
    private lateinit var server: FakeHttpServer
    private lateinit var client: SubsonicClient

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, VeldtDatabase::class.java).allowMainThreadQueries().build()
        val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val box = SecretBox(object : KeyProvider {
            override fun secretKey(): SecretKey = key
        })
        accounts = AccountRepository(
            dao = db.accountDao(),
            box = box,
            files = SecretFiles(ctx),
            newId = { "acct-1" },
        )
        server = FakeHttpServer().also { it.start() }
        client = SubsonicClient(http = OkHttpClient.Builder().build(), random = Random(42))
    }

    @After fun tearDown() {
        server.close()
        db.close()
    }

    /**
     * A [CoroutineDispatcher] that never runs anything handed to it — see [sources] for why
     * [SubsonicSources] needs one here instead of a real scope.
     *
     * Two things that look like they would stop [SubsonicSources]' init-time account-change
     * collector do NOT: cancelling the scope's [Job][kotlinx.coroutines.Job] before
     * construction does not, because cancellation is cooperative and the collector's body
     * still runs up to its first real suspension; and `runTest`'s own `backgroundScope` does
     * not either, because Room's generated `Flow` for `observeAll()` hops onto ITS OWN
     * internal query executor for the actual native SQLite call, regardless of the collecting
     * coroutine's dispatcher. Both were tried and reproduced the same failure: with a live
     * collector, an in-flight query can still be executing when THIS test's `db.close()` (or
     * even a later test's, once Robolectric tears down the sandbox that owned the connection)
     * runs out from under it, surfacing as `kotlinx.coroutines.test
     * .UncaughtExceptionsBeforeTest` on some UNRELATED test that merely happened to run next —
     * eight different classes, identically, across three separate full-suite runs.
     *
     * Overriding [dispatch] to swallow every `Runnable` is the one guarantee strong enough to
     * rely on: `CoroutineStart.DEFAULT` resumes the new coroutine through THIS dispatcher, and
     * a dispatcher that never runs what it is given means the collector's body — the
     * `accountDao.observeAll()` call itself — never executes at all, so there is no query left
     * to race against this test's teardown. No test below needs that collector to run: every
     * account row this file writes happens BEFORE the [SubsonicSources] that reads it is
     * constructed, so its synchronous construction-time snapshot already has it (see that
     * class's own KDoc).
     */
    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Deliberately does nothing — see the class KDoc.
        }
    }

    private fun sources(): SubsonicSources =
        SubsonicSources(db.accountDao(), db.songDao(), accounts, CoroutineScope(SupervisorJob() + NoOpDispatcher))

    private fun loader() = RemoteArtLoader(client, sources())

    /** A real, decodable 4x4 PNG — see the class KDoc for why this must be genuine bytes. */
    private fun pngBytes(width: Int = 4, height: Int = 4): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test fun `a PNG body decodes to a real bitmap`() = runTest {
        accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        server.enqueueBytes(pngBytes(), 200, "image/png")

        val bitmap = loader().load(TrackRef("acct-1", "s1"), 300)

        assertTrue("expected a decoded bitmap, got null", bitmap != null)
        assertEquals(listOf(4, 4), listOf(bitmap!!.width, bitmap.height))
    }

    @Test fun `the request carries the external id and the requested size`() = runTest {
        accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        server.enqueueBytes(pngBytes(), 200, "image/png")

        loader().load(TrackRef("acct-1", "s1"), 300)

        assertEquals("s1", server.queryParam(0, "id"))
        assertEquals("300", server.queryParam(0, "size"))
    }

    @Test fun `formPost capabilities put the id, size and credentials in the request body`() = runTest {
        val added = accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        val sourceId = (added as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $added")
        db.accountDao().upsert(db.accountDao().get(sourceId)!!.copy(capabilities = "formPost"))
        server.enqueueBytes(pngBytes(), 200, "image/png")

        loader().load(TrackRef(sourceId, "s1"), 300)

        val request = server.requests[0]
        assertEquals("POST", request.method)
        assertTrue("id=s1 must be in the form body", "id=s1" in request.body)
        assertTrue("size=300 must be in the form body", "size=300" in request.body)
        assertTrue("the plaintext password went on the wire: ${request.body}", "hunter2" !in request.body)
    }

    /** A JSON error envelope (an id the server does not recognise) must not be mistaken for a
     *  cover and must not crash the ladder. */
    @Test fun `a JSON error envelope resolves to null, not a crash`() = runTest {
        accounts.add("Home", server.baseUrl, "kyle", "hunter2")
        server.enqueue(
            """{"subsonic-response":{"status":"failed","version":"1.16.1",
                "error":{"code":70,"message":"Data not found"}}}""",
        )

        assertNull(loader().load(TrackRef("acct-1", "no-such-song"), 300))
    }

    /**
     * Spec §10: zero accounts / no credentials means zero requests. Asserted on the server's
     * own request log, not merely on the return value, so a defect that still made the (wasted)
     * network call — just to discard its answer — would be caught here.
     */
    @Test fun `an unknown source makes zero requests and returns null`() = runTest {
        // Deliberately no accounts.add(): "unknown-account" names no row at all.
        val bitmap = loader().load(TrackRef("unknown-account", "s1"), 300)

        assertNull(bitmap)
        assertEquals("credentials() must short-circuit before any network call", emptyList<Any>(), server.requests)
    }
}
