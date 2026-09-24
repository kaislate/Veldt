// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.content.ComponentName
import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.kaislate.veldtplayer.data.library.MusicRepository
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
    private val network: NetworkReturn,
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
     *
     * Counts *skips only* — see [nextConsecutiveErrors]. An [ErrorAction.PAUSE_IN_PLACE] or
     * [ErrorAction.STOP] has not consumed an item, and if it bumped this then a long outage would
     * walk the bound below and stop playback regardless, which is exactly what pausing exists to
     * prevent.
     */
    private var consecutiveErrors = 0

    /** "Resume after a network pause" (task 5, carried gap 3) — see [ResumeGate] and
     *  [ResumeCoordinator]'s KDoc for the decision rules and the registration lifecycle this class
     *  relies on without re-deriving them. */
    private val resumeCoordinator = ResumeCoordinator(ResumeGate(), network)

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
            // Whether this error is a property of the ITEM, the NETWORK, or the ACCOUNT. Skipping
            // is right for the first and ruinous for the other two: every subsequent item hits
            // the same dead network or the same rejected password, so the whole queue walks into
            // the bound below. See errorAction for which codes do what and, more to the point,
            // why bad-HTTP-status does not pause. The counter is assigned from the action rather
            // than incremented up front, so a pause or a stop cannot walk the bound.
            val action = errorAction(error.errorCode)
            consecutiveErrors = nextConsecutiveErrors(consecutiveErrors, action)
            if (action == ErrorAction.STOP) {
                // The server rejected the saved password. "Couldn't play <title>" would blame the
                // track; say what is actually wrong and where to fix it. Stay put — every other
                // remote track in the queue would be rejected the same way.
                _errors.tryEmit(REJECTED_PASSWORD)
                controller?.pause()
                // Task 4+5 review item 1: without this, a resume that lands on a STILL-broken
                // account (e.g. the rejected password again) would leave resumeCoordinator armed on
                // this item, and the NEXT unrelated network change would auto-play straight back
                // into the same rejection — up to its cap, entirely unasked.
                resumeCoordinator.disarm()
                return
            }
            val title = _nowPlaying.value.title.ifBlank { "this track" }
            _errors.tryEmit("Couldn't play “$title”")
            if (action == ErrorAction.PAUSE_IN_PLACE) {
                // Stay on this item, at this position — prepare() is deliberately NOT called here;
                // re-preparing into a still-dead network just re-enters this listener. Instead,
                // resumeCoordinator arms on the item and the network current right now (see
                // ResumeGate's KDoc for why the immediate callback for THIS network must not itself
                // count as a return), and its network callback re-prepares once a genuinely
                // different network shows up, up to its cap. A user tap on play is still the
                // fallback if the network never changes at all.
                controller?.pause()
                controller?.let { c ->
                    resumeCoordinator.arm(
                        itemIndex = c.currentMediaItemIndex,
                        mediaId = c.currentMediaItem?.mediaId,
                        state = {
                            val cc = controller
                            ResumeCoordinator.QueueState(
                                index = cc?.currentMediaItemIndex ?: -1,
                                mediaId = cc?.currentMediaItem?.mediaId,
                                playWhenReady = cc?.playWhenReady ?: false,
                            )
                        },
                        resume = { controller?.let { cc -> cc.prepare(); cc.play() } },
                    )
                }
                return
            }
            // A dead or undecodable file must not kill the whole queue — but the skip-on
            // cannot be unbounded. Under REPEAT_MODE_ALL the timeline wraps last -> first,
            // so hasNextMediaItem() is PERMANENTLY true; a queue where every item is
            // undecodable (SD card unmounted, files moved out from under stale MediaStore
            // rows) would re-prepare through the extractor forever. Stop once every item
            // has failed in a row. publish() clears the counter on the first STATE_READY.
            // Task 4+5 review item 1: a skip moves off the paused item, so nothing should still be
            // watching for it to come back.
            resumeCoordinator.disarm()
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

    /**
     * Appends [songs] after whatever is already queued, starting playback only if nothing was
     * (spec §3.2). No-op for an empty list.
     *
     * The decision is [QueueBuilder.append]'s, not this method's: everything below the plan needs a
     * live `MediaController` and is therefore unreachable from the JVM suite, so nothing that could
     * be got wrong is allowed to live down here. What is left is which of two Media3 calls to make.
     */
    @MainThread
    fun addToQueue(songs: List<Song>) {
        val plan = QueueBuilder.append(_queue.value, songs) ?: return
        _queue.value = plan.songs
        if (plan.startPlayback) {
            // A fresh queue, so the same reset playFrom does: a counter left high by a previous
            // all-undecodable queue would otherwise suppress skip-on for this one.
            consecutiveErrors = 0
            withController { c ->
                c.setMediaItems(plan.songs.map(::toMediaItem), 0, 0L)
                c.prepare()
                c.play()
            }
        } else {
            // Appended at the END rather than at an index computed from _queue, which the
            // publish() TODO already notes can lag the controller's real timeline. addMediaItems
            // with no index cannot be out of range; an index taken from a stale queue can.
            withController { c -> c.addMediaItems(songs.map(::toMediaItem)) }
        }
    }

    @MainThread
    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }

    @MainThread
    fun next() {
        // The user is navigating OFF whatever item might be paused (task 4+5 review round 2, item
        // b): a network-paused item left behind must never auto-resume once the user has moved
        // away from it, and a skip on an otherwise-idle player may never reach STATE_READY to
        // disarm it the ordinary way. Unconditional (not inside withController): navigation intent
        // is real the instant it is called, whether or not a controller is connected yet.
        resumeCoordinator.disarm()
        withController { it.seekToNextMediaItem() }
    }

    @MainThread
    fun previous() {
        resumeCoordinator.disarm()
        withController { it.seekToPreviousMediaItem() }
    }

    /** Seeking WITHIN the current item does not change which item is paused, so — unlike [next],
     *  [previous] and [skipToQueueIndex] — this deliberately leaves resumeCoordinator alone. */
    @MainThread
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    @MainThread
    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            resumeCoordinator.disarm()
            c.seekTo(index, 0L)
        }
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
        // Unregister BEFORE dropping the controller: a leaked NetworkCallback holds the
        // ConnectivityManager singleton's registration and keeps firing for the life of the
        // process, and a callback firing after this point would find controller already null.
        resumeCoordinator.disarm()
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
     *  `LibrarySource` resolution seam stays intact for non-local sources. The item itself
     *  is built by [sessionMediaItem], which is where its contents are asserted. */
    private fun toMediaItem(song: Song): MediaItem = sessionMediaItem(song, repo.playableUri(song))

    @MainThread
    private fun publish() {
        val c = controller ?: return
        if (c.playbackState == Player.STATE_READY) {
            consecutiveErrors = 0
            // A resume (or an ordinary play) reached READY: disarm resumeCoordinator (unregisters
            // its network callback — item 3) and refill ResumeGate's cap, so a later, unrelated
            // pause is not left starting from an old count.
            resumeCoordinator.onReady()
        }
        // TODO(p1.4): the current Song is resolved by indexing _queue, which only this
        //  connection ever fills. Playback started OUTSIDE it — session restore via
        //  MediaSession.Callback.onPlaybackResumption, a real browse tree, Android Auto —
        //  leaves _queue empty, so nowPlaying stays EMPTY and the mini-player renders blank
        //  while audio plays. Fix by hydrating _queue from c.currentTimeline / media IDs.
        //  Filing this here, not against the mini-player task: the symptom shows up there
        //  but the cause is this line.
        //
        //  N0 made "hydrating from media IDs" concrete. A mediaId is `sourceId:externalId`
        //  (see sessionMediaItem), so hydration is: split at the FIRST ':' — exact, because
        //  SourceRegistry bans ':' in a source id — then look the pair up as the natural key
        //  `(sourceId, externalId)`, which is the songs table's unique index. Note what this
        //  deliberately is NOT: the surrogate Song.id is absent from the mediaId precisely so
        //  that a restored session still resolves after a destructive migration has renumbered
        //  every row. Do not "simplify" the hydration by putting the surrogate back in.
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
        const val REJECTED_PASSWORD =
            "Your server rejected the saved password. Update it in Settings → Music servers."
    }
}
