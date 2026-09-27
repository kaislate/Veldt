// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.kaislate.veldtplayer.MainActivity
import com.kaislate.veldtplayer.data.art.RemoteArtLoader
import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import com.kaislate.veldtplayer.pill.PillController
import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicAuth
import com.kaislate.veldtplayer.data.net.SubsonicClient
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlusher
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlushScheduler
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.playback.scrobble.ListenClock
import com.kaislate.veldtplayer.playback.scrobble.Scheduler
import com.kaislate.veldtplayer.playback.scrobble.Scrobbler
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.playback.queue.QueueRestore
import com.kaislate.veldtplayer.playback.queue.QueueResumption
import com.kaislate.veldtplayer.playback.queue.QueueSaveScheduler
import com.kaislate.veldtplayer.playback.queue.QueueSaveTriggers
import com.kaislate.veldtplayer.playback.queue.QueueStore
import com.kaislate.veldtplayer.playback.queue.SavedQueue
import com.kaislate.veldtplayer.playback.queue.applyTo
import com.kaislate.veldtplayer.playback.queue.snapshotOf
import com.kaislate.veldtplayer.playback.queue.toResumption
import androidx.core.content.ContextCompat
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Media3 MediaLibraryService: hosts the ExoPlayer and publishes a
 * MediaLibrarySession. Media3 auto-manages the media notification and the
 * mediaPlayback foreground service. The browsable library tree is empty in
 * P1.1 (default callback) — it is filled in P1.2.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    /**
     * The service-side half of the logical playback uri (spec §4.4). Injected rather than
     * constructed because what it routes through — the `Set<RemoteUriResolver>` multibinding and
     * the server accounts' `RemoteResolverLookup` — is Hilt's to assemble.
     */
    @Inject lateinit var uriResolver: PlaybackUriResolver

    /**
     * So the notification, lock screen, Android Auto and the pill show a streamed track's
     * server art (spec §5.6), the same as `VeldtApp`'s Coil `AlbumArtFetcher.Factory`.
     *
     * [Lazy], matching `VeldtApp`'s own field — see that class's KDoc. `RemoteArtLoader`'s
     * dependency chain ends at `SubsonicSources`, whose `@Inject constructor` starts a LIVE
     * background collector against the real Room database, and this service's fields ARE
     * injected eagerly by Hilt on construction. No existing test builds a `PlaybackService`
     * today, but deferring construction to [onCreate] (where it is actually needed) costs
     * nothing and avoids the same class of bug if one ever does.
     */
    @Inject lateinit var remoteArt: Lazy<RemoteArtLoader>

    /** N3 (design spec §3, §5, §6): what [Scrobbler]'s `sender`/`sourceExists` are built from —
     *  the account this streams from, and the client that talks to it. Neither is `Lazy`: unlike
     *  [remoteArt], nothing here is touched from `VeldtApp`'s own eager injection, so the
     *  Robolectric-suite-leak [remoteArt]'s KDoc describes does not apply. */
    @Inject lateinit var subsonicSources: SubsonicSources
    @Inject lateinit var subsonicClient: SubsonicClient
    @Inject lateinit var scrobbleQueue: ScrobbleQueue
    @Inject lateinit var scrobbleFlusher: ScrobbleFlusher
    @Inject lateinit var scrobbleFlushScheduler: ScrobbleFlushScheduler

    /**
     * The built-in pill (P1.5c, spec §3), owned here so there is no second foreground service:
     * started at the end of [onCreate], released FIRST in [onDestroy] — before the bus is reset,
     * so the pill never redraws from an emptied bus, and before anything else can fail, so its
     * overlay window can never outlive the service.
     */
    @Inject lateinit var pillController: PillController

    /**
     * What a restored queue is checked against (spec §3): a saved track whose row is gone is
     * dropped. [Lazy] for the reason [remoteArt] is — `MusicRepository` reaches the Room database
     * and the settings DataStore, and nothing needs either until the restore coroutine runs.
     */
    @Inject lateinit var library: Lazy<MusicRepository>

    private var player: ExoPlayer? = null
    private var session: MediaLibrarySession? = null
    private var busAdapter: PlayerBusAdapter? = null
    private var bitmapLoader: VeldtBitmapLoader? = null
    private var scrobbler: Scrobbler? = null
    private var scrobblerListener: Player.Listener? = null
    private var scrobblerScope: CoroutineScope? = null

    // ---- queue persistence (spec §3) ----
    private var queueStore: QueueStore? = null
    private var queueSaver: QueueSaveScheduler? = null
    private var queueTriggers: Player.Listener? = null
    /** One thread, so saves land in the order they were taken and the last one wins. */
    private var queueWriter: ExecutorService? = null
    private var queueScope: CoroutineScope? = null

    /**
     * The restore's outcome: the queue put on the player, or null when there was nothing to put.
     * Completes exactly once — from the restore coroutine, or from [onDestroy] if the service dies
     * first — because [LibraryCallback.onPlaybackResumption] may be waiting on it: a Bluetooth
     * "play" that cold-starts the service arrives while the restore is still reading the database.
     */
    private val restored: SettableFuture<SavedQueue?> = SettableFuture.create()

    /**
     * The newest queue this service knows: the restored one, then every save's snapshot. Main
     * thread only. What resumption falls back to when the player is empty, and null — no file, an
     * emptied queue — is what makes it opt out (spec §3).
     */
    private var latestQueue: SavedQueue? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        // Hilt injects in the generated base class's onCreate, so `uriResolver` is only safe to
        // touch below this line.
        super.onCreate()
        val exo = ExoPlayer.Builder(this)
            // The builder's own default is `DefaultMediaSourceFactory(context,
            // DefaultExtractorsFactory())`, whose whole use of the context is
            // `DefaultDataSource.Factory(context)` (disassembly, N0). PlayerDataSources keeps that
            // shape for local files and inserts the resolver, the error-envelope guard and the
            // no-retry-on-envelope policy — see its KDoc for the order and why it matters.
            .setMediaSourceFactory(PlayerDataSources.mediaSourceFactory(this, uriResolver))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player = exo

        // Without this Media3 uses DataSourceBitmapLoader, which would open whatever
        // `artworkUri` says and hand the bytes to BitmapFactory — so the ONLY artwork uri
        // that could ever work is one no default loader understands. See [VeldtArtUri].
        //
        // CacheBitmapLoader is what lets the session and the bus adapter share one decode:
        // both ask for the current track's cover, and it holds the last request. (The
        // session would wrap the loader in one anyway; wrapping here puts the adapter
        // inside the same cache instead of outside it.)
        val loader = VeldtBitmapLoader(this, remoteArt.get())
        bitmapLoader = loader
        val sessionLoader = CacheBitmapLoader(loader)

        session = MediaLibrarySession.Builder(this, exo, LibraryCallback())
            .setSessionActivity(appLaunchIntent())
            .setBitmapLoader(sessionLoader)
            .build()
        busAdapter = PlayerBusAdapter(exo, packageName, sessionLoader).also { it.attach() }

        // N3: attached beside PlayerBusAdapter, same reasoning — this must work with the UI gone,
        // so it lives on the service's own player listener, not on anything Compose-scoped.
        //
        // Dispatchers.IO, not Main (review fix round 1, item 5): every `scope.launch` inside
        // Scrobbler is queue file I/O plus the scrobble network call — Scrobbler's own KDoc
        // documents that this scope choice is the ONE thing that decides where that launched
        // work runs, and (fix round 2, finding 3) EVERY ScrobbleQueue call Scrobbler makes now
        // actually lives inside one of those launches, not in the synchronous body of a public
        // method — so this really is true, not merely intended. Clock/player-state mutations
        // never touch this scope at all — they run synchronously, on the main thread, inside
        // the calls ScrobblerPlayerListener makes below — so nothing about that moves off main
        // by choosing IO here.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scrobblerScope = scope
        val instance = Scrobbler(
            scope = scope,
            // elapsedRealtime, never currentTimeMillis — see ListenClock's own KDoc on why.
            clock = ListenClock(now = SystemClock::elapsedRealtime),
            sourceExists = subsonicSources::contains,
            sender = { track, submission, timeMs ->
                val creds = subsonicSources.credentials(track.sourceId)
                if (creds == null) {
                    ScrobbleResult.Unreachable
                } else {
                    subsonicClient.scrobble(
                        creds,
                        subsonicSources.capabilities(track.sourceId),
                        track.externalId,
                        submission,
                        timeMs,
                    )
                }
            },
            queue = scrobbleQueue,
            flush = scrobbleFlusher::flush,
            enqueueFlush = scrobbleFlushScheduler::enqueue,
            scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
        )
        scrobbler = instance
        ScrobblerPlayerListener(exo, instance).also {
            scrobblerListener = it
            exo.addListener(it)
        }

        startQueueRestore(exo)

        // Last: the bus adapter above is already publishing, so the pill's first read is real.
        pillController.start()
    }

    /**
     * Spec §3: put the saved queue back on the player, PAUSED and NOT prepared, then start saving.
     *
     * Asynchronous because checking the saved tracks still exist is a database read, and
     * `onCreate` is on the main thread. Until it finishes the player is empty, which is why:
     * - saving is not armed until it finishes — a save of the still-empty player would delete the
     *   very file being restored;
     * - the restored queue is only applied if the player is STILL empty. A controller that got in
     *   first (the app's own `playFrom` during the first frames, or Media3 applying
     *   [LibraryCallback.onPlaybackResumption]'s result) has said what should play, and a restore
     *   landing on top of it would replace the user's choice with yesterday's.
     */
    private fun startQueueRestore(exo: ExoPlayer) {
        val store = QueueStore(File(filesDir, QUEUE_DIR))
        queueStore = store
        queueWriter = Executors.newSingleThreadExecutor()
        val saver = QueueSaveScheduler(
            scheduler = HandlerScheduler(Handler(Looper.getMainLooper())),
            save = { persistQueue(waitForWrite = false) },
        )
        queueSaver = saver
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        queueScope = scope
        scope.launch {
            val saved = withContext(Dispatchers.IO) { loadRestorableQueue(store) }
            val applied = saved != null && exo.mediaItemCount == 0
            if (applied) saved!!.applyTo(exo)
            latestQueue = saved
            restored.set(saved)
            QueueSaveTriggers(saver).also {
                queueTriggers = it
                exo.addListener(it)
            }
            // Whatever got onto the player while the restore ran produced its events before the
            // triggers were listening; catch up on them once.
            if (!applied && exo.mediaItemCount > 0) saver.requestSave()
            saver.setPlaying(exo.isPlaying)
        }
    }

    /** The saved queue, with tracks that no longer resolve dropped — see [QueueRestore]. */
    private suspend fun loadRestorableQueue(store: QueueStore): SavedQueue? {
        val saved = store.read() ?: return null
        val repo = library.get()
        // A database that cannot be read is NOT "every track is gone": null restores as saved.
        val rows = runCatching { repo.findByKeys(QueueRestore.refsOf(saved)) }.getOrNull()
        return QueueRestore.restorable(saved, rows, repo::playableUri)
    }

    /**
     * Snapshots the player (main thread — the player's thread) and writes it on [queueWriter].
     * [waitForWrite] blocks, bounded, until that write is done: for [onDestroy], where the process
     * may be gone the moment this returns. Waiting on the same single-thread executor, rather than
     * writing inline, is what keeps an older save still queued there from landing AFTER this one.
     *
     * A no-op until the restore has finished — see [startQueueRestore].
     */
    private fun persistQueue(waitForWrite: Boolean) {
        if (!restored.isDone) return
        val exo = player ?: return
        val store = queueStore ?: return
        val writer = queueWriter ?: return
        val snapshot = snapshotOf(exo)
        latestQueue = snapshot
        val write = runCatching { writer.submit { store.write(snapshot) } }.getOrNull() ?: return
        if (waitForWrite) runCatching { write.get(FLUSH_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    /** Spec §3: swiping the app away is a save trigger — the process may not live to the next. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        persistQueue(waitForWrite = true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // FIRST (plan Review Focus 5): removes the overlay window. See [pillController].
        pillController.release()
        // The final save, while the player still holds the queue; then nothing more is scheduled.
        persistQueue(waitForWrite = true)
        queueSaver?.release()
        queueTriggers?.let { player?.removeListener(it) }
        queueScope?.cancel()
        queueWriter?.shutdown()
        // Anything still waiting on the restore gets "nothing" rather than a future that never
        // completes (a no-op if the restore already finished).
        restored.set(null)
        busAdapter?.detach()
        // Detach first, then clear: the adapter can push during teardown, and a push landing
        // after the reset would refill the bus with the state this is meant to drop.
        //
        // MediaSessionBus is a process-scoped singleton, so without this it keeps serving the
        // dead service's last track — and holds its decoded full-size cover bitmap — for as
        // long as the process lives.
        MediaSessionBus.reset()
        // Listener removed, then the Scrobbler released, before the player itself is released —
        // release() only cancels a pending "played" timer; removeListener first is what stops any
        // FURTHER event from reaching a Scrobbler already told to shut down.
        scrobblerListener?.let { player?.removeListener(it) }
        scrobbler?.release()
        scrobblerScope?.cancel()
        session?.release()
        player?.release()
        // Cancels any art load still walking the ladder. Safe in any order relative to the
        // reset above only because `detach()` already invalidated the adapter's in-flight
        // request: release completes those futures exceptionally, which still runs their
        // listeners.
        bitmapLoader?.release()
        session = null
        player = null
        busAdapter = null
        bitmapLoader = null
        scrobbler = null
        scrobblerListener = null
        scrobblerScope = null
        queueStore = null
        queueSaver = null
        queueTriggers = null
        queueWriter = null
        queueScope = null
        super.onDestroy()
    }

    /**
     * What tapping the media notification (or Samsung's media panel, or the lock
     * screen controls) opens. Without a session activity the platform has nothing
     * to launch and the tap is silently inert — `dumpsys media_session` reports
     * `launchIntent=null`.
     *
     * `FLAG_UPDATE_CURRENT` so a re-created service replaces rather than
     * duplicates the intent; `FLAG_IMMUTABLE` is required from API 31 and is
     * correct here regardless, since nothing needs to fill in extras.
     */
    private fun appLaunchIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // Resume the existing task rather than stacking a second copy of the
            // activity on top of the one the user already has.
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Default player-command handling (play/pause/seek/next/prev) is inherited. */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Spec §3's system resumption: what Media3 1.8.0 asks for when a "play" reaches a player
         * with no current item — a Bluetooth or headset button, the resume card, a car starting
         * cold — and, for System UI's resume card, when it asks the session for its "recent" item.
         *
         * Media3 only offers resumption at all when the app declares
         * `androidx.media3.session.MediaButtonReceiver` for `MEDIA_BUTTON` (1.8.0's
         * `MediaSessionLegacyStub.canResumePlaybackOnStart` is exactly "a receiver was found");
         * see the manifest.
         *
         * Which queue answers, and the opt-out when there is none, is [QueueResumption]'s.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val main = ContextCompat.getMainExecutor(this@PlaybackService)
            val queue = QueueResumption(
                restored = restored,
                live = { player?.let(::snapshotOf) },
                latest = { latestQueue },
                executor = main,
            ).resume()
            return Futures.transform(queue, { it.toResumption() }, main)
        }
    }

    private companion object {
        /** `filesDir/playback/queue.json` (spec §3). */
        const val QUEUE_DIR = "playback"

        /** How long [onDestroy]/[onTaskRemoved] wait for the final queue write. A write of the
         *  capped queue is a few hundred KB at most; this only bounds a stuck disk. */
        const val FLUSH_TIMEOUT_MS = 2_000L
    }
}

/**
 * The real `Player.Listener` that drives [Scrobbler] (N3, design spec §3/§5/§6) from actual
 * Media3 events — deliberately thin and NOT unit-tested: [Scrobbler]'s own KDoc explains why the
 * interesting logic lives there instead, driven directly by [com.kaislate.veldtplayer.playback
 * .scrobble.ScrobblerTest]'s fakes.
 *
 * [MediaItem.localConfiguration]`.uri` is where `SessionMediaItem.sessionMediaItem` put the
 * logical playback uri (`veldt://track/…` for a server track, a `content://` for a local one);
 * [VeldtUri.parse] is what turns the former into a [TrackRef] and the latter into null — the same
 * null a track whose uri failed to resolve at all would also produce, and both are indistinguishable
 * here on purpose: [Scrobbler] treats "not a server track" and "we cannot tell" identically —
 * neither one is ever scrobbled.
 *
 * **`player.isPlaying` is passed into every [Scrobbler.onMediaItemTransition] call** (review fix
 * round 1, item 1 — CRITICAL): Media3 fires `onMediaItemTransition` on gapless auto-advance and on
 * a repeat-one wrap WITHOUT a following `onIsPlayingChanged`, because the playing state never
 * actually changes across either kind of transition. Without this, every track after the first
 * in a continuously-playing queue would get no scrobble at all until the user next paused.
 *
 * **Every [Scrobbler.onDurationKnown] call also passes `currentTrackRef()`** (review fix round 2,
 * finding 1): Media3 delivers `onTimelineChanged` for a queue replacement BEFORE
 * `onMediaItemTransition`, and by then `player.currentMediaItem` already reports the NEW item —
 * so a duration read at that moment describes the NEW item, not whatever [Scrobbler] still
 * considers current. Passing the identity alongside the value is what lets [Scrobbler] itself
 * refuse to apply it to the wrong play-through, rather than this facade trying to guess.
 */
private class ScrobblerPlayerListener(
    private val player: Player,
    private val scrobbler: Scrobbler,
) : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        scrobbler.onMediaItemTransition(trackRefOf(mediaItem), durationOf(mediaItem), player.isPlaying)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        scrobbler.onIsPlayingChanged(isPlaying)
    }

    /** A placeholder `MediaItem`'s timeline entry can gain its real duration/metadata after
     *  `onMediaItemTransition` already fired (review fix round 1, item 3) — `player.duration`
     *  alone would miss that correction until the next `STATE_READY`, which may be seconds and
     *  several accumulated playing-seconds later. */
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        val current = player.currentMediaItem
        scrobbler.onDurationKnown(trackRefOf(current), durationOf(current))
    }

    /** `player.duration` is commonly `C.TIME_UNSET` at [onMediaItemTransition] time (before the
     *  item is prepared) and becomes known once the player reaches [Player.STATE_READY] — see
     *  [Scrobbler.onDurationKnown]'s KDoc for what this corrects. */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState != Player.STATE_READY) return
        val current = player.currentMediaItem
        scrobbler.onDurationKnown(trackRefOf(current), durationOf(current))
    }

    /** [VeldtUri.parse] of [MediaItem.localConfiguration]'s uri — see the class KDoc — factored
     *  out because [onTimelineChanged]/[onPlaybackStateChanged] now need it alongside a duration
     *  read, not just [onMediaItemTransition]. */
    private fun trackRefOf(mediaItem: MediaItem?): TrackRef? =
        mediaItem?.localConfiguration?.uri?.toString()?.let(VeldtUri::parse)

    /**
     * `player.duration` when the player actually knows it; otherwise the `MediaItem`'s own
     * CATALOG duration (`SessionMediaItem` sets `mediaMetadata.durationMs` from `Song.durationMs`
     * — review fix round 1, item 3), which is known immediately at enqueue time, long before the
     * player prepares anything. Only when NEITHER is available does this fall through to
     * `C.TIME_UNSET`, which [ListenClock] itself treats as the 240s-cap "unknown" case.
     */
    private fun durationOf(mediaItem: MediaItem?): Long {
        val fromPlayer = player.duration
        if (fromPlayer != C.TIME_UNSET) return fromPlayer
        return mediaItem?.mediaMetadata?.durationMs ?: C.TIME_UNSET
    }
}

/** [Scheduler] backed by a real main-looper [Handler] — the only Android-touching implementation
 *  of the seam [Scrobbler] uses for its "played" timer, mirroring [NetworkReturn.listen]'s own
 *  cancel-lambda shape (see [Scheduler]'s own KDoc). */
private class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMs)
        return { handler.removeCallbacks(runnable) }
    }
}

/**
 * Media3's hook for resolving a uri at LOAD time (spec §4.4), holding [PlaybackUriResolver].
 *
 * A named class rather than the lambda `ResolvingDataSource.Factory` would accept, so
 * [resolveDataSpec] is reachable from a JVM test without standing up an `ExoPlayer` —
 * see `ResolvingDataSourceWiringTest`.
 *
 * ### What `ResolvingDataSource` actually does
 *
 * Read off the 1.8.0 bytecode rather than taken from documentation, because §4.4 flags this
 * interaction as the one that must be verified rather than asserted:
 *
 * - `ResolvingDataSource.open` calls [resolveDataSpec] **once per open** and hands the result
 *   straight to the upstream `DataSource.open`. Every open is a fresh call, so a token minted here
 *   is minted per request rather than frozen into the queue — which is the point of the whole
 *   indirection.
 * - `Resolver.resolveReportedUri` is a **default-identity** method (`aload_1; areturn`) called from
 *   exactly one place: `ResolvingDataSource.getUri()`, applied to whatever the *upstream* reports —
 *   post-redirect, for http. It is **not** the cache key; see below.
 *
 * ### Why the cache identity is pinned with `key`, not with `resolveReportedUri`
 *
 * `CacheKeyFactory.DEFAULT` is `dataSpec.key ?: dataSpec.uri.toString()`, and `CacheDataSource.open`
 * evaluates it against **the `DataSpec` it is handed**, then stamps the result back into
 * `DataSpec.key` before delegating. Nothing on that path ever consults `DataSource.getUri()`, so
 * overriding `resolveReportedUri` could not keep a cache entry stable across a rotated token.
 * Setting [DataSpec.key] can, and does so whichever side of this resolver a cache is later
 * installed on: outside, and the cache computes the key from the still-logical uri and we re-set
 * the same value; inside, and the key we set is the one it reads.
 *
 * An upstream-chosen key is never overwritten. A `CacheDataSource` enclosing this one has already
 * stamped its own, and replacing it would split one track across two cache entries.
 *
 * ### Why `resolveReportedUri` IS overridden — to redact, not to map back (N2)
 *
 * What it changes is the uri in `LoadEventInfo`: `StatsDataSource` overwrites its `lastOpenedUri`
 * with `getUri()` after a successful open, and every analytics listener sees that. Once resolution
 * produces a real `stream` url, that uri carries `t=` and `s=`, so it is passed through
 * [SubsonicAuth.redact] (spec §4.4 correction). It is a pure function of its input on purpose: the
 * `Resolver` is a *single* instance shared by every `DataSource` the factory creates, so a
 * resolved-url-to-logical-uri mapping would have nowhere race-free to live, while a redaction
 * needs none.
 */
@OptIn(UnstableApi::class)
internal class VeldtDataSpecResolver(
    private val uris: PlaybackUriResolver,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val requested = dataSpec.uri.toString()
        val resolved = uris.resolve(requested)
        if (resolved == requested) {
            // Unchanged is ambiguous by itself: it is either a `content://` uri nothing here owns
            // (the passthrough below), or a `veldt://track/…` uri that PlaybackUriResolver could not
            // route to anything — the account was removed, or its credentials could not be read
            // (spec Review Focus 5). The second case must NOT reach DefaultDataSource: it hands the
            // unrecognised `veldt` scheme to DefaultHttpDataSource, which throws
            // `MalformedURLException: unknown protocol: veldt`, surfacing as 2001
            // (IO_NETWORK_CONNECTION_FAILED) — a PAUSE_IN_PLACE that no retry, including the
            // network-return resume, can ever fix, since the item itself is the problem. Raising
            // ERROR_CODE_REMOTE_REFUSED here instead routes it through the same "the server refused
            // THIS item" policy a live 404 or 403 already gets: SKIP, once, and move on.
            if (VeldtUri.parse(requested) != null) {
                throw DataSourceException("no source can resolve this track", ERROR_CODE_REMOTE_REFUSED)
            }
            // The passthrough, and the reason this layer is invisible to local playback. Returning
            // the *same* `DataSpec` matters rather than an equal one: `withUri`/`buildUpon` allocate a
            // copy on every open of every `content://` track, and `DataSpec` declares no `equals`, so
            // a copy is not interchangeable with the original to anything that compares them. Global
            // Constraint 5 lives on this line.
            return dataSpec
        }
        return dataSpec.buildUpon()
            .setUri(resolved)
            .setKey(dataSpec.key ?: requested)
            .build()
    }

    /**
     * The upstream's uri with every credential value replaced. See the class KDoc.
     *
     * A uri with nothing to redact — every `content://` load — comes back as the same object, not
     * a re-parse of it, for the reason the passthrough in [resolveDataSpec] returns the same spec.
     */
    override fun resolveReportedUri(uri: Uri): Uri {
        val text = uri.toString()
        val redacted = SubsonicAuth.redact(text)
        return if (redacted == text) uri else Uri.parse(redacted)
    }
}
