// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.ListenableFuture
import com.kaislate.veldtplayer.data.art.SongArt
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Robolectric because every input and output here is a framework type: [Uri] parsing, real
 * [Bitmap] instances, and `MimeTypes` normalisation. `isReturnDefaultValues` would hand back
 * nulls for all three, so a JVM-only version of these tests would pass against a loader that
 * does nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class VeldtBitmapLoaderTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun songArt(
        id: Long = 42L,
        uri: String = "content://media/external/audio/media/42",
        filePath: String? = "/storage/emulated/0/Music/a.mp3",
        hasEmbeddedArt: Boolean = true,
    ) = SongArt(songId = id, uri = uri, filePath = filePath, hasEmbeddedArt = hasEmbeddedArt)

    /** Blocks briefly rather than forever: a pending future is a FAILURE here, not a wait. */
    private fun <T> ListenableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

    private fun ListenableFuture<Bitmap>.failureCause(): Throwable {
        try {
            get(5, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            return e.cause ?: e
        }
        fail("Expected the future to complete exceptionally, but it produced a bitmap")
        error("unreachable")
    }

    // ---------- the art ladder ----------

    @Test fun `a veldt artwork uri resolves through the existing art ladder`() {
        val cover = bitmap()
        val seen = mutableListOf<SongArt>()
        val loader = VeldtBitmapLoader(loadArt = { art -> seen += art; cover })

        val art = songArt()
        val result = loader.loadBitmap(VeldtArtUri.of(art)).await()

        // The bitmap the ladder produced is the bitmap Media3 gets — nothing re-decodes it.
        assertSame(cover, result)
        // ...and the ladder was asked about THIS song, with every field it needs to walk it.
        assertEquals(listOf(art), seen)
    }

    /**
     * The uri is the only thing carrying a track's identity to the loader, so two tracks
     * must arrive at the ladder as two songs. Asserted as a pair: if the encoding collapsed
     * them the failure reads `expected:<[SongArt(songId=1..), SongArt(songId=2..)]> but
     * was:<[SongArt(songId=1..), SongArt(songId=1..)]>` — the collapse itself.
     *
     * It matters concretely: Media3 wraps this loader in `CacheBitmapLoader`, which caches
     * by uri, so two tracks with equal artwork uris would share one cover.
     */
    @Test fun `two different songs reach the ladder as two different songs`() {
        val seen = mutableListOf<SongArt>()
        val loader = VeldtBitmapLoader(loadArt = { art -> seen += art; bitmap() })

        val first = songArt(id = 1L, uri = "content://media/external/audio/media/1")
        val second = songArt(id = 2L, uri = "content://media/external/audio/media/2")
        loader.loadBitmap(VeldtArtUri.of(first)).await()
        loader.loadBitmap(VeldtArtUri.of(second)).await()

        assertEquals(listOf(first, second), seen)
    }

    // ---------- every future completes ----------

    /**
     * A track whose sources all fail. Media3 holds notification rendering on this future, so
     * "no art" has to be an ANSWER; leaving it pending would hang the notification rather
     * than draw it coverless.
     */
    @Test fun `an unreadable uri completes the future exceptionally rather than pending`() {
        val loader = VeldtBitmapLoader(loadArt = { null })

        val future = loader.loadBitmap(VeldtArtUri.of(songArt()))

        assertTrue(future.failureCause() is VeldtBitmapLoader.NoArtwork)
        assertTrue(future.isDone)
    }

    /** A source that throws — a malformed container, a revoked permission — is the same answer. */
    @Test fun `a throwing art source completes the future exceptionally`() {
        val boom = IllegalStateException("malformed container")
        val loader = VeldtBitmapLoader(loadArt = { throw boom })

        assertSame(boom, loader.loadBitmap(VeldtArtUri.of(songArt())).failureCause())
    }

    /**
     * Not a Veldt uri: refused without being attempted. The refusal must still be a
     * completion — an unrecognised uri that parked its future would hang exactly as an
     * unreadable one would.
     */
    @Test fun `a foreign uri is refused without reaching the ladder`() {
        val seen = mutableListOf<SongArt>()
        val loader = VeldtBitmapLoader(loadArt = { art -> seen += art; bitmap() })

        val future = loader.loadBitmap(Uri.parse("content://media/external/audio/media/42"))

        assertTrue(future.failureCause() is IllegalArgumentException)
        assertEquals(emptyList<SongArt>(), seen)
    }

    /**
     * Release cancels the scope. Cancellation that merely abandoned the coroutine would
     * leave the future pending forever, which is the one outcome Media3 cannot recover from.
     */
    @Test fun `releasing the loader completes an in-flight future exceptionally`() {
        val gate = CompletableDeferred<Bitmap>()
        val loader = VeldtBitmapLoader(loadArt = { gate.await() })

        val future = loader.loadBitmap(VeldtArtUri.of(songArt()))
        loader.release()

        // Any completion will do; the assertion is that it is DONE and not waiting on `gate`.
        future.failureCause()
        assertTrue(future.isDone)
    }

    /**
     * The default constructor's delegate is the real [com.kaislate.veldtplayer.data.art.AlbumArtFetcher]
     * ladder — the tests above stub that seam, so this is the one that proves the seam is
     * wired to it at all. Nothing here is readable (no MediaStore row, no file on disk), so
     * every rung fails and the future must still finish.
     */
    @Test fun `the production loader walks the real ladder and still completes`() {
        val loader = VeldtBitmapLoader(context)

        val future = loader.loadBitmap(
            VeldtArtUri.of(songArt(uri = "content://media/external/audio/media/999999"))
        )

        assertTrue(future.failureCause() is VeldtBitmapLoader.NoArtwork)
        loader.release()
    }

    // ---------- the trap ----------

    /**
     * **The guard against decoding the track as a picture.**
     *
     * Media3 asks a loader what it can handle. A loader that answered "audio" would be
     * inviting the framework to hand it the MP3 itself — the same failure as pointing
     * `artworkUri` at the audio uri, arriving through the other door, and just as silent:
     * `BitmapFactory` returns null on audio bytes, so the symptom is a blank cover, not a
     * crash.
     *
     * Asserted as one pair of lists so the failure message IS the collapse — a loader that
     * said yes to everything reads
     * `expected:<[true, true, false, false, false, false]> but was:<[true, true, true, true,
     * true, true]>` rather than "expected false but was true" with no hint of which side
     * moved.
     */
    @Test fun `supportsMimeType claims images and refuses audio`() {
        val loader = VeldtBitmapLoader(loadArt = { bitmap() })
        val types = listOf(
            "image/jpeg", "image/png",
            "audio/mpeg", "audio/flac", "audio/mp4", "audio/ogg",
        )

        assertEquals(
            types.zip(listOf(true, true, false, false, false, false)),
            types.map { it to loader.supportsMimeType(it) },
        )
    }
}
