package com.kaislate.veldtplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The ONE MediaController in the process (global constraint 6). App-scoped, so the
 * mini-player, now-playing screen, queue sheet and browse screens all observe the same
 * player state and issue commands through the same connection.
 *
 * Deliberately does NOT read [com.kaislate.veldtplayer.data.media.MediaSessionBus]: that
 * bus is the pill's frozen one-way contract (framework types, no queue, no commands).
 * MediaController is the canonical Media3 client API and already carries the timeline.
 *
 * **Threading: every command method is main-thread only.** `MediaController` enforces this
 * itself (`verifyApplicationThread` throws otherwise), the connect callback is delivered on
 * the application looper, and [pending] is a plain unsynchronized [ArrayDeque] mutated from
 * both. Calling a command from `Dispatchers.IO` races the drain and throws. The builder pins
 * the application looper to main explicitly so this holds no matter which thread happens to
 * trigger the first injection.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MusicRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    /**
     * The connect future can complete *after* this object is no longer wanted, handing
     * back a live controller nobody will ever release. Same guard idiom the P1.1/P1.2
     * ViewModels used: a flag plus `future.cancel(false)`.
     */
    private var released = false

    /**
     * Set when the connect future completed exceptionally. There is no retry, so parking
     * further commands would leak lambdas into a deque nobody drains — and silently, which
     * is the failure mode this class exists to remove. Commands are refused loudly instead.
     */
    private var connectFailed = false

    /**
     * Commands issued before the controller finishes connecting. P1.2 dropped these
     * outright, so a tap during the first second of app launch did nothing. They are
     * replayed in order on connect.
     */
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    /**
     * Consecutive failed items, so the skip-on in [Player.Listener.onPlayerError] cannot spin
     * forever. Reset by [publish] the moment anything reaches `STATE_READY`.
     */
    private var consecutiveErrors = 0

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _nowPlaying = MutableStateFlow(NowPlayingState.EMPTY)
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * MediaController does not push position continuously, so it is polled — but only
     * while something is subscribed (WhileSubscribed) and only quickly while actually
     * playing. A backgrounded UI costs nothing.
     */
    val positionMs: StateFlow<Long> = flow {
        while (true) {
            emit(controller?.currentPosition ?: 0L)
            delay(if (_nowPlaying.value.isPlaying) TICK_PLAYING_MS else TICK_IDLE_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onPlayerError(error: PlaybackException) {
            val title = _nowPlaying.value.title.ifBlank { "this track" }
            _errors.tryEmit("Couldn't play “$title”")
            consecutiveErrors++
            // A dead or undecodable file must not kill the whole queue — but the skip-on
            // cannot be unbounded. Under REPEAT_MODE_ALL the timeline wraps last -> first,
            // so hasNextMediaItem() is PERMANENTLY true; a queue where every item is
            // undecodable (SD card unmounted, files moved out from under stale MediaStore
            // rows) would re-prepare through the extractor forever. Stop once every item
            // has failed in a row. publish() clears the counter on the first STATE_READY.
            controller?.let { c ->
                if (consecutiveErrors >= c.mediaItemCount) return
                if (c.hasNextMediaItem()) {
                    c.seekToNextMediaItem()
                    c.prepare()
                    c.play()
                }
            }
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            // Pin the application looper rather than inheriting the injecting thread's, so
            // the command methods' @MainThread contract holds even if some future background
            // entry point is the first thing to ask Hilt for this singleton.
            .setApplicationLooper(Looper.getMainLooper())
            .buildAsync()
        controllerFuture = future
        future.addListener({
            val built = runCatching { future.get() }.getOrNull()
            if (built == null) {
                // Connect failed, or release() cancelled it. Either way no controller is
                // ever coming: drop the parked commands instead of letting every subsequent
                // tap pile onto a deque nobody will drain, and tell the user — a silent
                // dead end is the exact bug this class exists to remove.
                pending.clear()
                if (!released) {
                    connectFailed = true
                    _errors.tryEmit(CONNECT_FAILED)
                }
                return@addListener
            }
            if (released) {
                built.release()
                return@addListener
            }
            controller = built
            built.addListener(listener)
            // Removal-before-invocation keeps the replay exactly-once and FIFO; the finally
            // stops a throwing block from stranding the rest of the queue forever (Guava's
            // listener runner swallows the exception).
            try {
                while (pending.isNotEmpty()) pending.removeFirst()(built)
            } finally {
                pending.clear()
            }
            publish()
        }, MoreExecutors.directExecutor())
    }

    // ---------- commands ----------

    /** Plays [songs] as the queue, starting at [index] (spec §5, play-in-context). */
    @MainThread
    fun playFrom(songs: List<Song>, index: Int) {
        val plan = QueueBuilder.build(songs, index)
        if (plan.songs.isEmpty()) return
        _queue.value = plan.songs
        // A new queue is a fresh start: without this, a counter left high by a previous
        // all-undecodable queue would suppress skip-on for the next one.
        consecutiveErrors = 0
        withController { c ->
            c.setMediaItems(plan.songs.map(::toMediaItem), plan.startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    @MainThread
    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }

    @MainThread
    fun next() = withController { it.seekToNextMediaItem() }

    @MainThread
    fun previous() = withController { it.seekToPreviousMediaItem() }

    @MainThread
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    @MainThread
    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L)
    }

    @MainThread
    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    @MainThread
    fun cycleRepeat() = withController { c ->
        c.repeatMode = RepeatModes.toPlayer(RepeatModes.next(RepeatModes.fromPlayer(c.repeatMode)))
    }

    /**
     * Drops the connection. **Terminal and one-way — there is no reconnect path.** After
     * this, the [scope] backing [positionMs] is cancelled and every command is a silent
     * no-op, while Hilt goes on handing out this same dead instance for the rest of the
     * process. Not part of the UI-facing API: it exists for process-teardown and test
     * symmetry, and to make the pending-future race explicit rather than accidental.
     */
    @MainThread
    internal fun release() {
        released = true
        controllerFuture?.cancel(false)
        controllerFuture = null
        pending.clear()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        scope.cancel()
    }

    // ---------- internals ----------

    @MainThread
    private fun withController(block: (MediaController) -> Unit) {
        if (released) return
        if (connectFailed) {
            _errors.tryEmit(CONNECT_FAILED)
            return
        }
        val c = controller
        if (c != null) block(c) else pending.addLast(block)
    }

    /** Goes through [MusicRepository.playableUri] rather than [Song.uri] directly, so the
     *  `LibrarySource` resolution seam stays intact for non-local sources. */
    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(Uri.parse(repo.playableUri(song)))
        // Through DisplayNames, like every other surface. This metadata is what the
        // notification, the lock screen and Android Auto render, so a raw tag here means
        // the one place the user cannot correct is also the one place still showing
        // "<unknown>" — or, once that is cleaned to blank, showing nothing at all.
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.displayTitle())
                .setArtist(song.displayArtist())
                .setAlbumTitle(song.displayAlbum())
                .build()
        )
        .build()

    @MainThread
    private fun publish() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_READY) consecutiveErrors = 0
        // TODO(p1.4): the current Song is resolved by indexing _queue, which only this
        //  connection ever fills. Playback started OUTSIDE it — session restore via
        //  MediaSession.Callback.onPlaybackResumption, a real browse tree, Android Auto —
        //  leaves _queue empty, so nowPlaying stays EMPTY and the mini-player renders blank
        //  while audio plays. Fix by hydrating _queue from c.currentTimeline / media IDs.
        //  Filing this here, not against the mini-player task: the symptom shows up there
        //  but the cause is this line.
        val song = _queue.value.getOrNull(c.currentMediaItemIndex)
        _nowPlaying.value = NowPlayingState.from(
            song = song,
            playState = PlaybackMapper.playState(c.playbackState, c.playWhenReady),
            playerDurationMs = c.duration,
            shuffle = c.shuffleModeEnabled,
            repeat = RepeatModes.fromPlayer(c.repeatMode),
            hasNext = c.hasNextMediaItem(),
            hasPrevious = c.hasPreviousMediaItem(),
        )
    }

    private companion object {
        const val TICK_PLAYING_MS = 250L
        const val TICK_IDLE_MS = 1_000L
        const val CONNECT_FAILED = "Couldn't connect to playback"
    }
}
