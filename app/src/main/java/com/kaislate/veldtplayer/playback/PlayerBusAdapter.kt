package com.kaislate.veldtplayer.playback

import android.media.MediaMetadata
import android.media.session.PlaybackState
import androidx.media3.common.Player
import com.kaislate.veldtplayer.data.media.MediaSessionBus

/**
 * Mirrors the internal Media3 [Player] state into the shared [MediaSessionBus]
 * so the (future) built-in pill can read Veldt's own playback exactly as it
 * reads any external app. State direction only in P1.1 (see plan Task 4 note).
 */
class PlayerBusAdapter(
    private val player: Player,
    private val pkg: String,
) {
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
    }

    private fun push() {
        val snap = PlaybackMapper.playState(player.playbackState, player.playWhenReady)
        MediaSessionBus.updatePlayback(buildPlaybackState(snap))
        MediaSessionBus.updateMetadata(buildMetadata())
        val art = player.mediaMetadata.artworkData
            ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        MediaSessionBus.setAlbumArt(art)
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
