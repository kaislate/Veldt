// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.audit

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
import com.kaislate.veldtplayer.data.art.RemoteArtLoader
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.data.library.sync.SubsonicSyncCoordinator
import com.kaislate.veldtplayer.data.library.sync.SubsonicSyncWorker
import com.kaislate.veldtplayer.data.library.sync.SyncStatusStore
import com.kaislate.veldtplayer.data.lyrics.LrclibCache
import com.kaislate.veldtplayer.data.lyrics.LrclibClient
import com.kaislate.veldtplayer.data.lyrics.LrclibProvider
import com.kaislate.veldtplayer.data.lyrics.ServerLyricsProvider
import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlusher
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.playback.NetworkMeter
import com.kaislate.veldtplayer.playback.PlaybackUriResolver
import com.kaislate.veldtplayer.playback.StreamQuality
import com.kaislate.veldtplayer.playback.SubsonicStreamResolvers
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.VeldtUri
import com.kaislate.veldtplayer.playback.scrobble.ListenClock
import com.kaislate.veldtplayer.playback.scrobble.Scheduler
import com.kaislate.veldtplayer.playback.scrobble.Scrobbler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
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
import java.io.IOException
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.coroutines.CoroutineContext

/**
 * Network spec §10 / design spec §7's five invariants, as tests, exercised against the REAL
 * components (not fakes) sharing ONE `OkHttpClient` whose interceptor [RecordingInterceptor]
 * records every request — including one that goes on to "fail" — and then throws, so this suite
 * never opens a real socket. That throw is exactly how every component already treats a dead
 * socket ([SubsonicClient.execute], [LrclibClient.get], [RemoteArtLoader.load]'s `coverArt`), so
 * nothing here needed a behavioural change to stay hermetic.
 *
 * No production DI was changed: every component already takes its [OkHttpClient] (or, for
 * [LrclibClient], its base url) as a constructor parameter, so this suite just constructs the
 * real classes directly with the recording client — see each component's own KDoc for that
 * seam already existing (Task 1–3's own work). Invariant 5 (airplane mode) is a device check
 * (N2), not a JVM test, and is out of scope here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineByDefaultAuditTest {

    // ------------------------------------------------------------------------------- the recorder

    /** Records [method]/[url]/[body] for every request an `OkHttpClient` built with this
     *  interceptor attempts, THEN throws — so a request that would have failed anyway (a dead
     *  socket, an unreachable host) is still recorded: the record happens before the throw, not
     *  instead of it. */
    private class RecordingInterceptor : Interceptor {
        data class Recorded(val method: String, val url: String, val body: String)

        val requests: MutableList<Recorded> = mutableListOf()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val bodyText = request.body?.let { body ->
                val buffer = Buffer()
                body.writeTo(buffer)
                buffer.readUtf8()
            }.orEmpty()
            requests += Recorded(request.method, request.url.toString(), bodyText)
            throw IOException("blocked by RecordingInterceptor: this suite must stay offline")
        }
    }

    private fun hostOf(url: String): String = url.toHttpUrl().host

    /** The last path segment before any query string — the Subsonic endpoint name — from either
     *  a [RecordingInterceptor.Recorded] url or a resolved stream url (review fix round 1, item
     *  7: "assert per-endpoint expected requests, not just at least one"). */
    private fun endpointOf(url: String): String = url.substringAfterLast('/').substringBefore('?')

    /** Real-time polling for [recorder]'s request count to reach [min] — needed only after a
     *  [Scrobbler] send, which is fire-and-forget by design; see [scrobbler]'s own KDoc. Every
     *  OTHER entry point in this file is a plain suspend call the test coroutine directly awaits,
     *  which needs no such wait. */
    private fun awaitRequests(min: Int, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (recorder.requests.size < min) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "expected at least $min recorded requests within ${timeoutMs}ms, got ${recorder.requests.size}",
                )
            }
            Thread.sleep(5)
        }
    }

    // ------------------------------------------------------------------------------------- setup

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var accountDao: AccountDao
    private lateinit var songDao: SongDao
    private lateinit var accounts: AccountRepository
    private lateinit var status: SyncStatusStore
    private lateinit var recorder: RecordingInterceptor
    private lateinit var http: OkHttpClient
    private lateinit var client: SubsonicClient
    private lateinit var queueDir: File
    private lateinit var queue: ScrobbleQueue
    private lateinit var lrclibCacheDir: File
    private var key: SecretKey? = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** How many times [scrobbler]'s built `enqueueFlush` callback ran, across a whole test —
     *  review fix round 1, item 7: invariant 1 must also assert the scheduler enqueues nothing. */
    private var enqueueFlushCount = 0

    /** [SubsonicSources]' `accountDao.observeAll()` collector never runs — see `ScrobbleFlusherTest`'s
     *  KDoc for why this dispatcher, and why [sources] below is built AFTER any account exists. */
    private object NoOpDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            // Deliberately does nothing.
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
        status = SyncStatusStore(context)
        runBlocking { status.clearForTest() }
        recorder = RecordingInterceptor()
        http = OkHttpClient.Builder()
            .addInterceptor(recorder)
            .connectTimeout(1, TimeUnit.SECONDS)
            .build()
        client = SubsonicClient(http = http, random = Random(42))
        queueDir = Files.createTempDirectory("audit-queue").toFile()
        queue = ScrobbleQueue(queueDir)
        lrclibCacheDir = Files.createTempDirectory("audit-lrclib-cache").toFile()
    }

    @After fun tearDown() {
        db.close()
        queueDir.deleteRecursively()
        lrclibCacheDir.deleteRecursively()
    }

    /** Built AFTER whatever accounts this test needs already exist — [SubsonicSources.contains]
     *  (which [SubsonicStreamResolvers], [ScrobbleFlusher] and [Scrobbler] all consult) reads a
     *  snapshot frozen at construction time under [NoOpDispatcher]. */
    private fun sources(): SubsonicSources =
        SubsonicSources(accountDao, songDao, accounts, CoroutineScope(SupervisorJob() + NoOpDispatcher))

    private suspend fun addAccount(host: String = "h.example:4533"): String {
        val result = accounts.add("Home", host, "kyle", "hunter2")
        return (result as? AccountWriteResult.Saved)?.sourceId ?: error("expected Saved, got $result")
    }

    private fun streamResolvers(sources: SubsonicSources): SubsonicStreamResolvers {
        val quality = StreamQuality(cap = flowOf(0), meter = NetworkMeter { false }, scope = CoroutineScope(Dispatchers.Unconfined))
        return SubsonicStreamResolvers(sources, quality, Random(42))
    }

    private fun lrclibProvider(enabled: Boolean): LrclibProvider {
        val lrclibClient = LrclibClient(http = http, userAgent = "VeldtAuditTest/1.0")
        val cache = LrclibCache(lrclibCacheDir) { 0L }
        return LrclibProvider(lrclibClient, cache) { enabled }
    }

    /** [flush] and [enqueueFlush] are the real thing / a no-op respectively: this suite cares
     *  about what reaches the network, not about re-arming WorkManager.
     *
     *  [Scrobbler] sends fire-and-forget (`scope.launch { ... }`, never awaited by design), and
     *  [SubsonicSources.credentials] genuinely dispatches onto `Dispatchers.IO` inside that
     *  launch — so a test that triggers a send and immediately reads [recorder] races it, EXACTLY
     *  the same shape `AccountsViewModelTest.awaitCalls` exists for (a `viewModelScope.launch`
     *  that genuinely suspends on real IO). [awaitRequests] is this file's version of that same
     *  fix — every test below that triggers a send through this method calls it before reading
     *  [recorder] (this was this file's own first bug, caught while writing it, not a deliberate
     *  control — see the task report for the real control this file performs instead). */
    private fun scrobbler(sources: SubsonicSources, flusher: ScrobbleFlusher): Scrobbler {
        val immediate = object : Scheduler {
            override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
                action() // fires synchronously; no test here waits on a real threshold timer
                return {}
            }
        }
        return Scrobbler(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            clock = ListenClock(now = { 0L }, wallClock = { 0L }),
            sourceExists = sources::contains,
            sender = { track, submission, timeMs ->
                val creds = sources.credentials(track.sourceId)
                if (creds == null) {
                    ScrobbleResult.Unreachable
                } else {
                    client.scrobble(creds, sources.capabilities(track.sourceId), track.externalId, submission, timeMs)
                }
            },
            queue = queue,
            flush = flusher::flush,
            enqueueFlush = { enqueueFlushCount++ },
            scheduler = immediate,
        )
    }

    private fun syncWorker(sourceId: String, flusher: ScrobbleFlusher, sources: SubsonicSources): SubsonicSyncWorker =
        TestListenableWorkerBuilder.from(context, SubsonicSyncWorker::class.java)
            .setInputData(workDataOf(SubsonicSyncWorker.KEY_SOURCE_ID to sourceId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = SubsonicSyncWorker(
                    appContext, workerParameters, client, sources, accounts, accountDao, songDao, status, flusher,
                ) { 1_000L }
            })
            .build()

    private fun localSongEntity(
        externalId: String = "local-1",
        filePath: String = "/storage/emulated/0/Music/x.mp3",
        title: String = "t",
        album: String = "al",
    ) = SongEntity(
        id = 0L, sourceId = "local", externalId = externalId, uri = "content://media/external/audio/media/1",
        filePath = filePath, relativeKey = "external_primary:Music/x.mp3",
        title = title, artist = "artist", album = album,
        albumArtist = null, trackNumber = null, discNumber = null, year = null,
        durationMs = 200_000L, dateModifiedSec = 100L, hasEmbeddedArt = false,
    )

    // ------------------------------------------------------------------------------ invariant 1

    /**
     * Network spec §10.1 / design spec §7.1: zero accounts, LRCLIB OFF, exercised across every
     * network-using entry point ⇒ 0 recorded requests, asserted ONCE at the end so the invariant
     * is "the whole run made no request", not "no single call did".
     *
     * LRCLIB stays OFF here on purpose (task brief): it is a separate opt-in the account count has
     * nothing to do with — see the next test for the "LRCLIB on with zero accounts" case, which
     * belongs to invariant 2's host-set rule instead.
     */
    @Test fun `zero accounts, LRCLIB off, makes zero requests across every entry point`() = runTest {
        val sources = sources() // no account exists yet: an empty snapshot either way
        val ghostId = "no-such-account"
        val ghostTrack = TrackRef(ghostId, "song-1")

        // sync. `SubsonicSyncCoordinator.request` itself enqueues a WorkManager job
        // UNCONDITIONALLY — it does not, and (fix round 2, finding 4) deliberately does NOT,
        // check whether the id names a current account first: `SubsonicSources.contains`/`byId`
        // read a snapshot that lags a just-added account (see `SubsonicSources.credentials`'s own
        // KDoc), and gating `request` on that check would silently drop a BRAND NEW account's
        // very first sync — a correctness regression far worse than this one test's precision.
        // Calling it here proves only that enqueueing itself touches no network (trivially true —
        // WorkManager scheduling is not a request) — the real "zero requests for a nonexistent
        // account" claim is what running the WORKER any such job would actually execute proves,
        // immediately below.
        SubsonicSyncCoordinator(context, songDao, status, queue).request(ghostId)
        assertEquals(
            ListenableWorker.Result.failure(workDataOf(SubsonicSyncWorker.KEY_FAILURE to "gone")),
            syncWorker(ghostId, ScrobbleFlusher(queue, client, sources), sources).doWork(),
        )

        // stream resolution
        val veldtUri = VeldtUri.track(ghostId, "song-1")
        val resolver = PlaybackUriResolver(emptySet(), streamResolvers(sources))
        assertEquals("an unresolvable account must pass the uri through unchanged", veldtUri, resolver.resolve(veldtUri))

        // cover art
        assertEquals(null, RemoteArtLoader(client, sources).load(ghostTrack, 300))

        // server lyrics
        val song = SongEntity(
            id = 0L, sourceId = ghostId, externalId = "song-1", uri = veldtUri,
            filePath = null, relativeKey = null, title = "t", artist = "a", album = "al",
            albumArtist = null, trackNumber = null, discNumber = null, year = null,
            durationMs = 200_000L, dateModifiedSec = 0L, hasEmbeddedArt = false,
        ).toDomain()
        assertEquals(null, ServerLyricsProvider(client, sources).lyricsFor(song))

        // lrclib, OFF
        assertEquals(null, lrclibProvider(enabled = false).lyricsFor(song))

        // scrobbler: an eligible-looking track whose source does not exist
        val flusher = ScrobbleFlusher(queue, client, sources)
        val bot = scrobbler(sources, flusher)
        bot.onMediaItemTransition(ghostTrack, 240_000L, isPlaying = false)
        bot.onIsPlayingChanged(true)

        // flusher, standalone (an empty queue, and a flush() call naming an unknown source)
        flusher.flushAll()
        flusher.flush(ghostId)

        assertEquals(
            "expected zero requests with zero accounts; got ${recorder.requests}",
            emptyList<RecordingInterceptor.Recorded>(),
            recorder.requests,
        )
        assertEquals(
            "the flush scheduler must never be enqueued with zero accounts",
            0,
            enqueueFlushCount,
        )
    }

    /**
     * The other half of the brief's note: with zero accounts but LRCLIB deliberately turned ON,
     * a LOCAL song's lyrics lookup DOES make one request — to lrclib.net, never to a phantom
     * account host. This is invariant 2's host-set rule (⊆ {accounts} ∪ {lrclib.net}) evaluated
     * at the zero-account edge, not a violation of invariant 1.
     */
    @Test fun `zero accounts with LRCLIB on requests only lrclib_net, for a local song`() = runTest {
        val localSong = localSongEntity().toDomain()
        lrclibProvider(enabled = true).lyricsFor(localSong)

        assertTrue("expected at least one request to lrclib.net", recorder.requests.isNotEmpty())
        assertEquals(
            setOf("lrclib.net"),
            recorder.requests.map { hostOf(it.url) }.toSet(),
        )
    }

    // ------------------------------------------------------------------------------ invariant 2

    /**
     * Network spec §10.4: every request targets a host the user typed. One account at
     * `h.example`, LRCLIB off ⇒ every recorded host is exactly that account's host — asserted
     * per-endpoint (review fix round 1, item 7), not merely "at least one", and INCLUDING the
     * resolved stream url (review fix round 1, item 2): a stream never goes through the recorded
     * `OkHttpClient` at all (Media3 opens it via `DefaultHttpDataSource`), so without this the
     * stream url's host was never checked by this file.
     */
    @Test fun `one account, LRCLIB off, every recorded host is that account's host`() = runTest {
        val sourceId = addAccount("h.example:4533")
        // BEFORE sources() — SubsonicSources.capabilities reads a snapshot frozen at
        // construction (see sources()'s KDoc); caching this after would leave
        // ServerLyricsProvider seeing BASELINE forever and silently contributing no request.
        accounts.cacheCapabilities(sourceId, setOf("songLyrics"))
        val sources = sources() // built AFTER the account (and its capabilities) exist
        val track = TrackRef(sourceId, "song-1")
        val veldtUri = VeldtUri.track(sourceId, "song-1")
        val flusher = ScrobbleFlusher(queue, client, sources)

        syncWorker(sourceId, flusher, sources).doWork()
        val resolvedStreamUrl = PlaybackUriResolver(emptySet(), streamResolvers(sources)).resolve(veldtUri)
        assertTrue("expected the stream uri to actually resolve, not pass through unchanged", resolvedStreamUrl != veldtUri)
        RemoteArtLoader(client, sources).load(track, 300)
        val song = SongEntity(
            id = 0L, sourceId = sourceId, externalId = "song-1", uri = veldtUri,
            filePath = null, relativeKey = null, title = "remote title", artist = "a", album = "al",
            albumArtist = null, trackNumber = null, discNumber = null, year = null,
            durationMs = 200_000L, dateModifiedSec = 0L, hasEmbeddedArt = false,
        ).toDomain()
        ServerLyricsProvider(client, sources).lyricsFor(song)
        scrobbler(sources, flusher).also {
            it.onMediaItemTransition(track, 240_000L, isPlaying = false)
            it.onIsPlayingChanged(true)
        }
        awaitRequests(1) // the scrobble send above is fire-and-forget — see scrobbler()'s KDoc
        queue.add(QueuedScrobble(sourceId, "song-2", 1_000L))
        flusher.flushAll()

        assertEquals(
            "expected exactly these Subsonic endpoints, got ${recorder.requests.map { it.url }}",
            setOf("getOpenSubsonicExtensions", "getAlbumList2", "getCoverArt", "getLyricsBySongId", "scrobble"),
            recorder.requests.map { endpointOf(it.url) }.toSet(),
        )
        assertEquals(
            "expected every host (including the resolved stream url) to be h.example",
            setOf("h.example"),
            (recorder.requests.map { hostOf(it.url) } + hostOf(resolvedStreamUrl)).toSet(),
        )
    }

    /** With LRCLIB also on, the host set widens to include `lrclib.net` and nothing else —
     *  including the resolved stream url (review fix round 1, item 2), and per-endpoint for the
     *  account host (review fix round 1, item 7). */
    @Test fun `one account, LRCLIB on, recorded hosts are the account host and lrclib_net, nothing else`() = runTest {
        val sourceId = addAccount("h.example:4533")
        val sources = sources()
        val flusher = ScrobbleFlusher(queue, client, sources)
        val veldtUri = VeldtUri.track(sourceId, "song-1")
        val resolvedStreamUrl = PlaybackUriResolver(emptySet(), streamResolvers(sources)).resolve(veldtUri)
        assertTrue("expected the stream uri to actually resolve, not pass through unchanged", resolvedStreamUrl != veldtUri)
        scrobbler(sources, flusher).also {
            it.onMediaItemTransition(TrackRef(sourceId, "song-1"), 240_000L, isPlaying = false)
            it.onIsPlayingChanged(true)
        }
        awaitRequests(1) // the scrobble send above is fire-and-forget — see scrobbler()'s KDoc
        val remoteSong = SongEntity(
            id = 0L, sourceId = sourceId, externalId = "song-1", uri = veldtUri,
            filePath = null, relativeKey = null, title = "remote title", artist = "a", album = "al",
            albumArtist = null, trackNumber = null, discNumber = null, year = null,
            durationMs = 200_000L, dateModifiedSec = 0L, hasEmbeddedArt = false,
        ).toDomain()
        lrclibProvider(enabled = true).lyricsFor(remoteSong)

        assertEquals(
            "expected exactly the scrobble endpoint on the account host",
            setOf("scrobble"),
            recorder.requests.filter { hostOf(it.url) == "h.example" }.map { endpointOf(it.url) }.toSet(),
        )
        assertEquals(
            "expected exactly {h.example, lrclib.net} (including the resolved stream url), nothing else",
            setOf("h.example", "lrclib.net"),
            (recorder.requests.map { hostOf(it.url) } + hostOf(resolvedStreamUrl)).toSet(),
        )
    }

    // ------------------------------------------------------------------------------ invariant 3

    /**
     * Network spec §10.3: no local file name, tag, or count ever crosses the wire. A local song is
     * planted with distinctive strings; every server-touching entry point is then run against a
     * SEPARATE remote account/song (never the planted one), and NONE of the recorded urls or
     * bodies may contain the planted strings. LRCLIB is exercised for a REMOTE song only — asking
     * LRCLIB about the planted LOCAL song would legitimately send its title/artist/album (that is
     * the whole point of an LRCLIB lookup), which would be a false alarm, not a real leak. The
     * RESOLVED STREAM URL (review fix round 1, item 2) is checked too — it never goes through the
     * recorded `OkHttpClient` (Media3 opens it via `DefaultHttpDataSource`), so without this a
     * leak into the stream url's query string would go unnoticed.
     */
    @Test fun `a planted local song's path, title and album never appear in any recorded request`() = runTest {
        val planted = localSongEntity(
            externalId = "local-plant",
            filePath = "/storage/emulated/0/Music/ZZ-PLANT-7f3a.mp3",
            title = "ZZ-PLANT-7f3a title",
            album = "ZZ-PLANT-7f3a album",
        )
        songDao.upsertBySourceKey(listOf(planted))

        val sourceId = addAccount("h.example:4533")
        // BEFORE sources() — see the "LRCLIB off" test's KDoc note for why the order matters.
        accounts.cacheCapabilities(sourceId, setOf("songLyrics"))
        val sources = sources()
        val flusher = ScrobbleFlusher(queue, client, sources)
        val remoteTrack = TrackRef(sourceId, "remote-song-1")
        val remoteVeldtUri = VeldtUri.track(sourceId, "remote-song-1")

        syncWorker(sourceId, flusher, sources).doWork()
        val resolvedStreamUrl = PlaybackUriResolver(emptySet(), streamResolvers(sources)).resolve(remoteVeldtUri)
        assertTrue("expected the stream uri to actually resolve, not pass through unchanged", resolvedStreamUrl != remoteVeldtUri)
        RemoteArtLoader(client, sources).load(remoteTrack, 300)
        val remoteSong = SongEntity(
            id = 0L, sourceId = sourceId, externalId = "remote-song-1", uri = remoteVeldtUri,
            filePath = null, relativeKey = null, title = "clean remote title", artist = "clean artist", album = "clean album",
            albumArtist = null, trackNumber = null, discNumber = null, year = null,
            durationMs = 200_000L, dateModifiedSec = 0L, hasEmbeddedArt = false,
        ).toDomain()
        ServerLyricsProvider(client, sources).lyricsFor(remoteSong)
        lrclibProvider(enabled = true).lyricsFor(remoteSong) // LRCLIB on a REMOTE song only — see the KDoc
        scrobbler(sources, flusher).also {
            it.onMediaItemTransition(remoteTrack, 240_000L, isPlaying = false)
            it.onIsPlayingChanged(true)
        }
        awaitRequests(1) // the scrobble send above is fire-and-forget — see scrobbler()'s KDoc
        queue.add(QueuedScrobble(sourceId, "remote-song-2", 1_000L))
        flusher.flushAll()

        assertTrue("expected at least one recorded request", recorder.requests.isNotEmpty())
        assertEquals("the resolved stream url must still target the account host", "h.example", hostOf(resolvedStreamUrl))
        val planted3 = listOf(
            "ZZ-PLANT-7f3a.mp3",
            "ZZ-PLANT-7f3a title",
            "ZZ-PLANT-7f3a album",
        )
        val checkedUrls = recorder.requests.map { it.url } + resolvedStreamUrl
        checkedUrls.forEach { url ->
            planted3.forEach { needle ->
                assertFalse("planted string \"$needle\" leaked into url $url", url.contains(needle))
            }
        }
        recorder.requests.forEach { req ->
            planted3.forEach { needle ->
                assertFalse(
                    "planted string \"$needle\" leaked into ${req.method} ${req.url} body=${req.body}",
                    req.body.contains(needle),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------ invariant 4

    /** Design spec §7.4 / plan Global Constraint 3: a local item is never scrobbled — reasserted
     *  here (Task 3's `ScrobblerTest` already covers this with fakes) against the same
     *  recording-interceptor real components as every other invariant in this file. */
    @Test fun `Scrobbler with a local item makes zero requests`() = runTest {
        val sourceId = addAccount("h.example:4533")
        val sources = sources()
        val flusher = ScrobbleFlusher(queue, client, sources)
        val bot = scrobbler(sources, flusher)

        bot.onMediaItemTransition(null, 240_000L, isPlaying = false) // a local item: no TrackRef at all
        bot.onIsPlayingChanged(true)

        assertEquals(emptyList<RecordingInterceptor.Recorded>(), recorder.requests)
        // Sanity: this same account WOULD generate a request for a real track — otherwise the
        // assertion above would be trivially true for the wrong reason (nothing here even wired).
        bot.onMediaItemTransition(TrackRef(sourceId, "song-1"), 240_000L, isPlaying = false)
        bot.onIsPlayingChanged(true)
        awaitRequests(1) // the scrobble send above is fire-and-forget — see scrobbler()'s KDoc
        assertTrue("expected the eligible track to have generated a request", recorder.requests.isNotEmpty())
    }
}
