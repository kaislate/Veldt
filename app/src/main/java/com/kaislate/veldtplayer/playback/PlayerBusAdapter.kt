// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
// `MediaMetadata` is already taken by the framework type this bus publishes.
import androidx.media3.common.MediaMetadata as Media3Metadata
import com.google.common.util.concurrent.MoreExecutors
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mirrors the internal Media3 [Player] state into the shared [MediaSessionBus]
 * so the (future) built-in pill can read Veldt's own playback exactly as it
 * reads any external app. State direction only in P1.1 (see plan Task 4 note).
 */
class PlayerBusAdapter(
    private val player: Player,
    private val pkg: String,
    bitmapLoader: BitmapLoader,
) {
    private val art = BusArtPublisher(bitmapLoader)

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) = push()
    }

    fun attach() {
        MediaSessionBus.activePackageForProducer(pkg)
        player.addListener(listener)
        push()
    }

    fun detach() {
        player.removeListener(listener)
        // Removing the listener stops NEW pushes but not the art load already in flight, and
        // `PlaybackService.onDestroy` calls `MediaSessionBus.reset()` immediately after this.
        // A cover landing in between would refill the bus the reset is there to empty.
        art.detach()
    }

    private fun push() {
        val snap = PlaybackMapper.playState(player.playbackState, player.playWhenReady)
        MediaSessionBus.updatePlayback(buildPlaybackState(snap))
        MediaSessionBus.updateMetadata(buildMetadata())
        art.publish(player.mediaMetadata)
    }

    private fun buildPlaybackState(snap: PlayState): PlaybackState {
        val state = when (snap) {
            PlayState.PLAYING -> PlaybackState.STATE_PLAYING
            PlayState.PAUSED -> PlaybackState.STATE_PAUSED
            PlayState.BUFFERING -> PlaybackState.STATE_BUFFERING
            PlayState.ENDED -> PlaybackState.STATE_STOPPED
            PlayState.IDLE -> PlaybackState.STATE_NONE
        }
        return PlaybackState.Builder()
            .setState(state, player.currentPosition, player.playbackParameters.speed)
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SEEK_TO or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
    }

    private fun buildMetadata(): MediaMetadata {
        val m = player.mediaMetadata
        return MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, m.title?.toString() ?: "")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, m.artist?.toString() ?: "")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, m.albumTitle?.toString() ?: "")
            .putLong(MediaMetadata.METADATA_KEY_DURATION,
                player.duration.let { if (it > 0) it else 0L })
            .build()
    }
}

/**
 * Publishes the current track's cover to [MediaSessionBus], loaded through the SAME
 * [BitmapLoader] the media session renders its notification with — so the pill and the
 * notification can never show different covers for one track.
 *
 * Split out of [PlayerBusAdapter] only so this can be tested without standing up a Media3
 * [Player]; it is one behaviour, not a layer.
 *
 * The load became asynchronous with the loader, which is what the two guards here are for.
 */
internal class BusArtPublisher(private val loader: BitmapLoader) {

    /**
     * Bumped on every genuine artwork change and on [detach]. A load that completes after
     * its bump is a load for a track that is no longer playing: publishing it would put the
     * PREVIOUS track's cover on the pill, which is the same stranded-cover failure the
     * `allowNull` below exists to prevent, arriving by a different route.
     *
     * **Atomic because the two sides of the guard run on different threads.** It is written
     * only from the main looper ([publish], [detach]), but it is READ in the future listener,
     * and that listener does not run on the main looper in the case that matters. Media3
     * wraps this loader in a `CacheBitmapLoader`, so a cache HIT completes on the caller's
     * thread — but every cache MISS, which is the first load of every track, is completed by
     * `VeldtBitmapLoader`'s coroutine on `Dispatchers.IO`, and `directExecutor` then runs the
     * listener on THAT thread. A plain `Int` gives the IO thread no happens-before edge to
     * the main thread's write, so it may read a stale generation, pass the check, and publish
     * the previous track's cover — the exact failure the guard exists to prevent.
     */
    private val generation = AtomicInteger(0)

    private var lastUri: Uri? = null
    private var lastData: ByteArray? = null

    /** Distinguishes "no artwork yet published" from "published, and it was null". */
    private var published = false

    fun publish(metadata: Media3Metadata) {
        // `onEvents` fires several times a second during playback. Without this the ladder
        // would re-run — a MediaStore IPC, or a full tag parse of the music file — for every
        // position discontinuity, decoding a full-size cover each time.
        if (published && metadata.artworkUri == lastUri && metadata.artworkData === lastData) {
            return
        }
        lastUri = metadata.artworkUri
        lastData = metadata.artworkData
        published = true

        val generationAtRequest = generation.incrementAndGet()
        // Null means the metadata carries neither artwork bytes nor an artwork uri: this
        // track genuinely has no cover, and there is nothing to wait for.
        val future = loader.loadBitmapFromMetadata(metadata) ?: return publishArt(null)
        future.addListener(
            {
                if (generationAtRequest != generation.get()) return@addListener
                // An exceptional completion is this loader's way of saying "no cover" — see
                // [VeldtBitmapLoader]. Either way the answer is null, not "keep the old one".
                publishArt(runCatching { future.get() }.getOrNull())
            },
            MoreExecutors.directExecutor(),
        )
    }

    /** Invalidates everything in flight. */
    fun detach() {
        generation.incrementAndGet()
    }

    /**
     * allowNull because this producer is authoritative about its own tracks: it publishes the
     * artwork of the item the player currently holds, so a null result means "this track has
     * no cover", not "no cover has arrived yet". Left at the default, a track with art
     * followed by one without would strand the first track's cover on screen for the rest of
     * the session.
     */
    private fun publishArt(bitmap: android.graphics.Bitmap?) =
        MediaSessionBus.setAlbumArt(bitmap, allowNull = true)
}
