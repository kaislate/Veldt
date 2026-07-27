package com.kaislate.veldtplayer.data.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyBitmap

class MediaSessionBusTest {

    // MediaSessionBus is a process-wide singleton, so leaving state behind would make
    // these tests pass or fail on execution order. Clearing first makes each independent
    // rather than relying on Robolectric's per-class classloader sandboxing, which is an
    // implementation detail of the runner and not a guarantee this file should lean on.
    @Before fun clean() = MediaSessionBus.reset()

    @Test fun `active package is published for the producer`() {
        MediaSessionBus.activePackageForProducer("com.kaislate.veldtplayer")
        assertEquals("com.kaislate.veldtplayer", MediaSessionBus.activePackage.value)
    }

    @Test fun `null metadata is ignored so the surface never blinks to a placeholder`() {
        MediaSessionBus.updateMetadata(null)
        // Nothing was ever set, so it stays null; the point is that it does not throw
        // and does not clear a previously good value.
        assertNull(MediaSessionBus.metadata.value)
    }

    @Test fun `reset clears producer state when the service dies`() {
        MediaSessionBus.activePackageForProducer("com.kaislate.veldtplayer")
        MediaSessionBus.reset()
        assertNull(MediaSessionBus.activePackage.value)
        assertNull(MediaSessionBus.playback.value)
        // The mirror has to go too, or a pill watching the cheap Int still reads
        // STATE_PLAYING after the service died and renders a playing pill for it.
        // Vacuous here — only `activePackage` was ever set, so the four other flows were
        // already null. `MediaSessionBusFrameworkTest` populates all five and re-asserts;
        // that is the copy with teeth.
        assertNull(MediaSessionBus.playbackState.value)
        assertNull(MediaSessionBus.metadata.value)
        assertNull(MediaSessionBus.albumArt.value)
    }
}

/**
 * A [Bitmap] whose pixels cannot be read back, which is what a `Config.HARDWARE` or
 * recycled cover is on a real device.
 *
 * Robolectric's own `ShadowBitmap` never throws from `sameAs` — the same gap
 * `ColorExtractorHardwareBitmapTest` documents for `getPixels` — so without this the
 * comparison-throws branch of `setAlbumArt` has no way to be reached, and deleting that
 * branch would leave every test green. Everything except `sameAs` is inherited, so
 * `createBitmap` and `eraseColor` still behave normally.
 */
@Implements(Bitmap::class)
class ShadowUnreadableBitmap : ShadowLegacyBitmap() {
    @Implementation
    override fun sameAs(other: Bitmap?): Boolean =
        throw IllegalStateException("Bitmap pixels are inaccessible")
}

/**
 * The behaviours below are the ones P1.1 paid for in bugs: a momentary null blanking a
 * good value, and an identical bitmap being re-emitted so consumers reload and flicker.
 * Both need real `Bitmap`/`MediaMetadata` instances, which `isReturnDefaultValues` cannot
 * supply — it would hand back a null builder result and a constant-false `sameAs`, so a
 * JVM test of either would pass against the deleted logic. Hence Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class MediaSessionBusFrameworkTest {

    @Before fun clean() = MediaSessionBus.reset()

    private fun art(color: Int): Bitmap =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun `a bitmap with identical pixels does not replace the current instance`() {
        val first = art(Color.RED)
        MediaSessionBus.setAlbumArt(first)
        MediaSessionBus.setAlbumArt(art(Color.RED))
        assertSame(first, MediaSessionBus.albumArt.value)
    }

    @Test fun `a bitmap with different pixels replaces the current instance`() {
        MediaSessionBus.setAlbumArt(art(Color.RED))
        val next = art(Color.BLUE)
        MediaSessionBus.setAlbumArt(next)
        assertSame(next, MediaSessionBus.albumArt.value)
    }

    /**
     * The pairing with the test above is what gives this one its teeth: identical pixels
     * are proven to be dropped, so when the comparison cannot run, the only way the new
     * instance wins is if a throw is being counted as "not equal". Treating a throw as
     * "equal" would strand the previous track's cover on screen for the whole session.
     */
    @Test
    @Config(shadows = [ShadowUnreadableBitmap::class])
    fun `art whose pixels cannot be compared is treated as different`() {
        val first = art(Color.RED)
        MediaSessionBus.setAlbumArt(first)
        val identicalButUnreadable = art(Color.RED)
        MediaSessionBus.setAlbumArt(identicalButUnreadable)
        assertSame(identicalButUnreadable, MediaSessionBus.albumArt.value)
    }

    @Test fun `null art is kept out unless the caller opts in`() {
        val first = art(Color.RED)
        MediaSessionBus.setAlbumArt(first)
        MediaSessionBus.setAlbumArt(null)
        assertSame(first, MediaSessionBus.albumArt.value)
        MediaSessionBus.setAlbumArt(null, allowNull = true)
        assertNull(MediaSessionBus.albumArt.value)
    }

    @Test fun `null metadata keeps the last known value`() {
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Sea Change")
            .build()
        MediaSessionBus.updateMetadata(meta)
        MediaSessionBus.updateMetadata(null)
        assertSame(meta, MediaSessionBus.metadata.value)
    }

    @Test fun `updatePlayback mirrors the state code so consumers can watch either flow`() {
        val state = playing()
        MediaSessionBus.updatePlayback(state)
        assertSame(state, MediaSessionBus.playback.value)
        assertEquals(PlaybackState.STATE_PLAYING, MediaSessionBus.playbackState.value)
    }

    /**
     * The version of this in [MediaSessionBusTest] asserts against flows that were never
     * populated, so it passes however `reset` is written. This one fills all five first —
     * which needs a real `PlaybackState` and `MediaMetadata`, hence its living here — so
     * each assertion can actually fail. Without it, dropping the `playbackState` line from
     * `reset` is silent, and P1.5 wires `reset` into `onDestroy`: a pill watching the
     * mirror would keep reading STATE_PLAYING and render a playing pill for a dead session.
     */
    @Test fun `reset clears every flow including the mirrored state code`() {
        MediaSessionBus.activePackageForProducer("com.kaislate.veldtplayer")
        MediaSessionBus.updatePlayback(playing())
        MediaSessionBus.updateMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "Sea Change")
                .build()
        )
        MediaSessionBus.setAlbumArt(art(Color.RED))

        // Guard: prove all five are non-null, or the assertions below prove nothing.
        assertNotNull(MediaSessionBus.activePackage.value)
        assertNotNull(MediaSessionBus.playback.value)
        assertNotNull(MediaSessionBus.playbackState.value)
        assertNotNull(MediaSessionBus.metadata.value)
        assertNotNull(MediaSessionBus.albumArt.value)

        MediaSessionBus.reset()

        assertNull(MediaSessionBus.activePackage.value)
        assertNull(MediaSessionBus.playback.value)
        assertNull(MediaSessionBus.playbackState.value)
        assertNull(MediaSessionBus.metadata.value)
        assertNull(MediaSessionBus.albumArt.value)
    }

    private fun playing(): PlaybackState = PlaybackState.Builder()
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
        .build()
}
