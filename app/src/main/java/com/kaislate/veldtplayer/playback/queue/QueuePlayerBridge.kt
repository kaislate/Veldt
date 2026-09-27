// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.MediaSession
import com.kaislate.veldtplayer.playback.VeldtArtUri

/*
 * The Media3 half of queue persistence: the translation between the player and [SavedQueue], and
 * the listener that decides a save is due. Deliberately thin — which items, which index and when
 * are [SavedQueues] and [QueueSaveScheduler]'s, both tested on the JVM; what is left here is
 * field-for-field copying that needs a real `MediaItem`.
 */

/** [item] as saved, or null when it has no playable uri — nothing could ever restore that. */
internal fun savedItemOf(item: MediaItem): SavedItem? {
    val uri = item.localConfiguration?.uri?.toString()?.takeIf { it.isNotEmpty() } ?: return null
    val meta = item.mediaMetadata
    return SavedItem(
        mediaId = item.mediaId,
        uri = uri,
        title = meta.title?.toString().orEmpty(),
        artist = meta.artist?.toString().orEmpty(),
        album = meta.albumTitle?.toString().orEmpty(),
        durationMs = meta.durationMs ?: 0L,
        art = meta.artworkUri?.let(VeldtArtUri::parse),
    )
}

/**
 * The inverse of [savedItemOf]: the same shape `sessionMediaItem` builds, so a restored item is
 * indistinguishable from one the app queued — the resolver sees the same logical uri, the
 * notification the same private-scheme artwork uri.
 */
internal fun SavedItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(Uri.parse(uri))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(art?.let(VeldtArtUri::of))
            .setDurationMs(durationMs)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build(),
    )
    .build()

/**
 * The player's queue as saved, capped (spec §3), or null when it is empty — which the store turns
 * into "no file", and resumption into an opt-out.
 */
@OptIn(UnstableApi::class)
internal fun snapshotOf(player: ExoPlayer): SavedQueue? {
    val count = player.mediaItemCount
    if (count == 0) return null
    val items = (0 until count).map { savedItemOf(player.getMediaItemAt(it)) }
    val order = shuffleOrderOf(player.shuffleOrder)
    val queue = SavedQueue(
        items = items.map { it ?: UNRESTORABLE },
        index = player.currentMediaItemIndex,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        shuffleEnabled = player.shuffleModeEnabled,
        shuffleOrder = order.takeIf { SavedQueues.isPermutation(it, count) },
        repeatMode = player.repeatMode,
    )
    // Uri-less items (only a foreign controller could have queued one) are dropped with the same
    // index rule as a deleted track, so the saved index still points at the same song.
    val savable = SavedQueues.pruned(queue) { it.takeUnless { item -> item === UNRESTORABLE } }
        ?: return null
    return SavedQueues.capped(savable)
}

/**
 * Puts [this] on [player] PAUSED and NOT prepared (spec §3): no decoder, no network, no audio focus
 * until the user presses play — which Media3's play handling turns into `prepare()` first
 * (`Util.handlePlayButtonAction`, 1.8.0).
 *
 * The shuffle order is set AFTER the items, because `setMediaItems` deals a fresh one.
 */
@OptIn(UnstableApi::class)
internal fun SavedQueue.applyTo(player: ExoPlayer) {
    player.playWhenReady = false
    player.setMediaItems(items.map { it.toMediaItem() }, index, positionMs)
    shuffleOrder?.let { order ->
        if (SavedQueues.isPermutation(order, items.size)) {
            player.setShuffleOrder(
                ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()),
            )
        }
    }
    player.shuffleModeEnabled = shuffleEnabled
    player.repeatMode = repeatMode
}

/** [this] in the shape `onPlaybackResumption` returns. */
internal fun SavedQueue.toResumption(): MediaSession.MediaItemsWithStartPosition =
    MediaSession.MediaItemsWithStartPosition(items.map { it.toMediaItem() }, index, positionMs)

@OptIn(UnstableApi::class)
private fun shuffleOrderOf(order: ShuffleOrder): List<Int> {
    val out = ArrayList<Int>(order.length)
    var i = order.firstIndex
    while (i != C.INDEX_UNSET && out.size < order.length) {
        out += i
        i = order.getNextIndex(i)
    }
    return out
}

private val UNRESTORABLE = SavedItem("", "", "", "", "", 0L, null)

/**
 * Spec §3's save triggers, as a player listener: item transition, play/pause, seek, queue edits,
 * shuffle and repeat changes request a (debounced) save; playing starts the 15 s periodic save and
 * pausing stops it. The service's `onTaskRemoved`/`onDestroy` call [QueueSaveScheduler.flush]
 * directly — those are not player events.
 *
 * Gapless auto-advance is covered by [onMediaItemTransition]; an automatic position discontinuity
 * is not a user seek and is left out so it does not double the write.
 */
internal class QueueSaveTriggers(private val saver: QueueSaveScheduler) : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = saver.requestSave()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = saver.requestSave()

    override fun onIsPlayingChanged(isPlaying: Boolean) = saver.setPlaying(isPlaying)

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK ||
            reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        ) {
            saver.requestSave()
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saver.requestSave()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saver.requestSave()

    override fun onRepeatModeChanged(repeatMode: Int) = saver.requestSave()
}
