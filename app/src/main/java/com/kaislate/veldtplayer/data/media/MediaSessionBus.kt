package com.kaislate.veldtplayer.data.media

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One-way publication of Veldt's own playback for in-process surfaces (the built-in pill,
 * wired in P1.5).
 *
 * Veldt is a MediaSession *producer*: it owns its player, so this bus only ever carries
 * state outward. Transport commands belong to
 * [com.kaislate.veldtplayer.playback.PlaybackConnection], which speaks to the session
 * through a `MediaController`; nothing here sends anything back to the player.
 *
 * The values are deliberately framework types ([MediaMetadata], [PlaybackState]) rather
 * than Media3 ones: a pill reads Veldt's session with the same code it would use for any
 * other app's, and [com.kaislate.veldtplayer.playback.PlayerBusAdapter] already builds
 * them.
 *
 * A singleton because the producer ([com.kaislate.veldtplayer.playback.PlaybackService])
 * and its consumers live in the same process but have no lifecycle in common — the
 * service can outlive every consumer and vice versa. [reset] is the counterweight: it is
 * the only way to clear the retained values when the producer goes away.
 */
object MediaSessionBus {

    private val _activePackage = MutableStateFlow<String?>(null)

    /** Package whose playback these flows describe; Veldt's own while the service runs. */
    val activePackage: StateFlow<String?> = _activePackage.asStateFlow()

    private val _playbackState = MutableStateFlow<Int?>(null)

    /** `PlaybackState.STATE_*` of [playback], mirrored for consumers that need only it. */
    val playbackState: StateFlow<Int?> = _playbackState.asStateFlow()

    private val _playback = MutableStateFlow<PlaybackState?>(null)

    /** Full transport state: state code, position, speed and advertised actions. */
    val playback: StateFlow<PlaybackState?> = _playback.asStateFlow()

    private val _metadata = MutableStateFlow<MediaMetadata?>(null)

    /** Current track's title/artist/album/duration. Never cleared by a null update. */
    val metadata: StateFlow<MediaMetadata?> = _metadata.asStateFlow()

    private val _albumArt = MutableStateFlow<Bitmap?>(null)

    /** Current artwork. Re-emitted only when the pixels differ (see [setAlbumArt]). */
    val albumArt: StateFlow<Bitmap?> = _albumArt.asStateFlow()

    /** Names the producer whose state follows. */
    fun activePackageForProducer(pkg: String) {
        _activePackage.value = pkg
    }

    /** Publishes transport state, keeping [playbackState] in step with it. */
    fun updatePlayback(playback: PlaybackState?) {
        _playback.value = playback
        _playbackState.value = playback?.state
    }

    /**
     * Publishes track metadata. **Null is accepted and defined as a no-op.**
     *
     * This is the contract, not a workaround: "I have nothing to publish right now" must
     * never be able to blank a surface. Letting a momentary null reach [metadata] would
     * make a consumer swap to a placeholder and straight back, which reads as a blink, so
     * the last good value stands until [reset] — the only thing that clears it.
     *
     * No current caller can trigger it: `PlayerBusAdapter.buildMetadata()` returns a
     * non-null [MediaMetadata]. The guard defends the flow's invariant against every
     * future caller, and holds whether or not one exists today.
     */
    fun updateMetadata(meta: MediaMetadata?) {
        if (meta == null) return
        _metadata.value = meta
    }

    /**
     * Publishes artwork, emitting only when the picture actually changes.
     *
     * The producer decodes a fresh [Bitmap] on every push, so consecutive pushes for one
     * track deliver distinct instances holding identical pixels. Emitting those would
     * make consumers reload the image and flicker, so an incoming bitmap that matches the
     * current one pixel for pixel is dropped and the existing instance kept.
     *
     * A null is treated as "no artwork available yet" and ignored unless [allowNull],
     * which callers pass when they mean "this track genuinely has no cover".
     */
    fun setAlbumArt(newArt: Bitmap?, allowNull: Boolean = false) {
        if (newArt == null) {
            if (allowNull) _albumArt.value = null
            return
        }
        val current = _albumArt.value
        if (current != null && showsSamePicture(current, newArt)) return
        _albumArt.value = newArt
    }

    /** Clears every flow. The hook for `PlaybackService.onDestroy` (wired in P1.5). */
    fun reset() {
        _activePackage.value = null
        _playbackState.value = null
        _playback.value = null
        _metadata.value = null
        _albumArt.value = null
    }

    /**
     * Dimensions first, since that rules out most pairs without touching pixels.
     *
     * Any failure means "not equal": a recycled or `Config.HARDWARE` bitmap cannot be read
     * back, and refusing to compare must never cost the caller a legitimate update — a
     * redundant emit is a flicker, a dropped one is the previous track's cover left on
     * screen for the rest of the session.
     *
     * Catching [Throwable] rather than [Exception] is deliberate: `sameAs` walks every
     * pixel of a full-size cover, so [OutOfMemoryError] is a plausible outcome here, and
     * "not equal" is the safe answer to it for exactly the same reason.
     */
    private fun showsSamePicture(current: Bitmap, incoming: Bitmap): Boolean = try {
        current.width == incoming.width &&
            current.height == incoming.height &&
            current.sameAs(incoming)
    } catch (t: Throwable) {
        false
    }
}
