// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pill's side of Task 9. `PlayerBusAdapter` used to read `player.mediaMetadata
 * .artworkData`, which Veldt never sets, so the bus artwork was permanently null; it now
 * goes through the session's own [BitmapLoader]. That load is asynchronous, and these are
 * the properties the asynchrony introduced.
 *
 * Robolectric because real [Bitmap]s are required: [MediaSessionBus.setAlbumArt] compares
 * pixels, and a stubbed bitmap would make every push look like a change.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class BusArtPublisherTest {

    @Before fun clean() = MediaSessionBus.reset()

    private fun art(color: Int): Bitmap =
        Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun songArt(id: Long) = SongArt(
        songId = id,
        uri = "content://media/external/audio/media/$id",
        filePath = "/storage/emulated/0/Music/$id.mp3",
        hasEmbeddedArt = true,
    )

    /** Metadata as `PlaybackConnection.toMediaItem` builds it: an artwork uri, no bytes. */
    private fun metadataFor(id: Long): MediaMetadata = MediaMetadata.Builder()
        .setTitle("Track $id")
        .setArtworkUri(VeldtArtUri.of(songArt(id)))
        .build()

    /** Metadata for a track with no cover: what `toMediaItem` produces is always a uri, but
     *  a controller-supplied item, or a future browse-tree item, can carry neither. */
    private fun metadataWithoutArtwork(): MediaMetadata =
        MediaMetadata.Builder().setTitle("Coverless").build()

    /** Hands out futures the test completes by hand, and records what it was asked for. */
    private class FakeLoader : BitmapLoader {
        val requested = mutableListOf<Uri>()
        val futures = mutableListOf<SettableFuture<Bitmap>>()

        override fun supportsMimeType(mimeType: String) = mimeType.startsWith("image/")

        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            SettableFuture.create<Bitmap>().apply { setException(UnsupportedOperationException()) }

        override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
            requested += uri
            return SettableFuture.create<Bitmap>().also { futures += it }
        }
    }

    // ---------- the stranded cover ----------

    /**
     * The behaviour `allowNull = true` exists for. A track WITH art followed by one WITHOUT
     * must clear the pill; dropping the flag makes [MediaSessionBus.setAlbumArt] ignore the
     * null, and the first track's cover stays on screen for the rest of the session.
     *
     * The first assertion is what gives the second teeth: without it, a publisher that never
     * set anything would also end with a null bus.
     */
    @Test fun `a track without art clears the cover left by the track before it`() {
        val loader = FakeLoader()
        val publisher = BusArtPublisher(loader)

        val cover = art(Color.RED)
        publisher.publish(metadataFor(1L))
        loader.futures[0].set(cover)
        assertSame(cover, MediaSessionBus.albumArt.value)

        publisher.publish(metadataWithoutArtwork())
        assertNull(MediaSessionBus.albumArt.value)
    }

    /** An exceptional completion is this pipeline's "no cover", so it must clear too. */
    @Test fun `a failed load clears the cover left by the track before it`() {
        val loader = FakeLoader()
        val publisher = BusArtPublisher(loader)

        val cover = art(Color.RED)
        publisher.publish(metadataFor(1L))
        loader.futures[0].set(cover)
        assertSame(cover, MediaSessionBus.albumArt.value)

        publisher.publish(metadataFor(2L))
        loader.futures[1].setException(VeldtBitmapLoader.NoArtwork())
        assertNull(MediaSessionBus.albumArt.value)
    }

    // ---------- ordering ----------

    /**
     * Track 1's ladder may finish after track 2 has already started. Asserted against the
     * pair of covers, so the failure names the one that won: publishing the late arrival
     * would put the PREVIOUS track's cover on the pill and leave it there.
     */
    @Test fun `a load that finishes late does not overwrite the newer track's cover`() {
        val loader = FakeLoader()
        val publisher = BusArtPublisher(loader)

        val first = art(Color.RED)
        val second = art(Color.BLUE)
        publisher.publish(metadataFor(1L))
        publisher.publish(metadataFor(2L))

        loader.futures[1].set(second)   // the current track resolves first
        loader.futures[0].set(first)    // ...then the one it replaced

        assertSame(second, MediaSessionBus.albumArt.value)
    }

    /**
     * `onEvents` fires several times a second. Each reload is a MediaStore round trip or a
     * full tag parse, so unchanged metadata must not trigger one. Asserted as the pair of
     * uris actually requested — a collapse into "reload every push" reads as a list with the
     * same uri twice.
     */
    @Test fun `unchanged metadata does not re-run the ladder`() {
        val loader = FakeLoader()
        val publisher = BusArtPublisher(loader)

        val one = metadataFor(1L)
        publisher.publish(one)
        publisher.publish(one)
        publisher.publish(metadataFor(1L))   // equal but a different instance
        publisher.publish(metadataFor(2L))

        assertEquals(
            listOf(VeldtArtUri.of(songArt(1L)), VeldtArtUri.of(songArt(2L))),
            loader.requested,
        )
    }

    /**
     * `PlaybackService.onDestroy` detaches the adapter and then resets the bus, in that
     * order and for that reason. A cover still walking the ladder would otherwise land
     * afterwards and refill the bus the reset just emptied — leaving a dead session's track
     * on the pill for as long as the process lives.
     */
    @Test fun `art still in flight at detach never reaches the bus`() {
        val loader = FakeLoader()
        val publisher = BusArtPublisher(loader)

        publisher.publish(metadataFor(1L))
        publisher.detach()
        MediaSessionBus.reset()
        loader.futures[0].set(art(Color.RED))

        assertNull(MediaSessionBus.albumArt.value)
    }
}
