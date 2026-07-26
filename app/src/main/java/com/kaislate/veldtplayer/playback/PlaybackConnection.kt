package com.kaislate.veldtplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
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
     * Commands issued before the controller finishes connecting. P1.2 dropped these
     * outright, so a tap during the first second of app launch did nothing. They are
     * replayed in order on connect.
     */
    private val pending = ArrayDeque<(MediaController) -> Unit>()

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
            // A dead or undecodable file must not kill the whole queue.
            controller?.let { c ->
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
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val built = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (released) {
                built.release()
                return@addListener
            }
            controller = built
            built.addListener(listener)
            while (pending.isNotEmpty()) pending.removeFirst()(built)
            publish()
        }, MoreExecutors.directExecutor())
    }

    // ---------- commands ----------

    /** Plays [songs] as the queue, starting at [index] (spec §5, play-in-context). */
    fun playFrom(songs: List<Song>, index: Int) {
        val plan = QueueBuilder.build(songs, index)
        if (plan.songs.isEmpty()) return
        _queue.value = plan.songs
        withController { c ->
            c.setMediaItems(plan.songs.map(::toMediaItem), plan.startIndex, 0L)
            c.prepare()
            c.play()
        }
    }

    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.seekTo(index, 0L)
    }

    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    fun cycleRepeat() = withController { c ->
        c.repeatMode = RepeatModes.toPlayer(RepeatModes.next(RepeatModes.fromPlayer(c.repeatMode)))
    }

    /**
     * Drops the connection. An app-scoped singleton normally lives for the whole process,
     * so this exists for process-teardown/test symmetry — and to make the pending-future
     * race explicit rather than accidental.
     */
    fun release() {
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

    private fun withController(block: (MediaController) -> Unit) {
        if (released) return
        val c = controller
        if (c != null) block(c) else pending.addLast(block)
    }

    private fun toMediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(Uri.parse(song.uri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.title)
                .setArtist(song.artist)
                .setAlbumTitle(song.album)
                .build()
        )
        .build()

    private fun publish() {
        val c = controller ?: return
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
    }
}
