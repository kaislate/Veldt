package com.kaislate.veldtplayer.data.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MediaSessionBusTest {

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
        assertNull(MediaSessionBus.metadata.value)
        assertNull(MediaSessionBus.albumArt.value)
    }
}

/**
 * The two behaviours below are the ones P1.1 paid for in bugs: a momentary null blanking
 * a good value, and an identical bitmap being re-emitted so consumers reload and flicker.
 * Both need real `Bitmap`/`MediaMetadata` instances, which `isReturnDefaultValues` cannot
 * supply, so this class runs under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class MediaSessionBusFramework {

    private fun art(color: Int): Bitmap =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun `a bitmap with identical pixels does not replace the current instance`() {
        MediaSessionBus.reset()
        val first = art(Color.RED)
        MediaSessionBus.setAlbumArt(first)
        MediaSessionBus.setAlbumArt(art(Color.RED))
        assertSame(first, MediaSessionBus.albumArt.value)
    }

    @Test fun `a bitmap with different pixels replaces the current instance`() {
        MediaSessionBus.reset()
        MediaSessionBus.setAlbumArt(art(Color.RED))
        val next = art(Color.BLUE)
        MediaSessionBus.setAlbumArt(next)
        assertSame(next, MediaSessionBus.albumArt.value)
    }

    @Test fun `null art is kept out unless the caller opts in`() {
        MediaSessionBus.reset()
        val first = art(Color.RED)
        MediaSessionBus.setAlbumArt(first)
        MediaSessionBus.setAlbumArt(null)
        assertSame(first, MediaSessionBus.albumArt.value)
        MediaSessionBus.setAlbumArt(null, allowNull = true)
        assertNull(MediaSessionBus.albumArt.value)
    }

    @Test fun `null metadata keeps the last known value`() {
        MediaSessionBus.reset()
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Sea Change")
            .build()
        MediaSessionBus.updateMetadata(meta)
        MediaSessionBus.updateMetadata(null)
        assertSame(meta, MediaSessionBus.metadata.value)
    }

    @Test fun `updatePlayback mirrors the state code so consumers can watch either flow`() {
        MediaSessionBus.reset()
        val state = android.media.session.PlaybackState.Builder()
            .setState(android.media.session.PlaybackState.STATE_PLAYING, 0L, 1f)
            .build()
        MediaSessionBus.updatePlayback(state)
        assertSame(state, MediaSessionBus.playback.value)
        assertEquals(
            android.media.session.PlaybackState.STATE_PLAYING,
            MediaSessionBus.playbackState.value,
        )
    }
}
