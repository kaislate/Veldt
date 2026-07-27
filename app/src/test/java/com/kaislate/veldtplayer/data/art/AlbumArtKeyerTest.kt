package com.kaislate.veldtplayer.data.art

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil.request.Options
import coil.request.Parameters
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the art cache key.
 *
 * The key used to be a constant expression and is now conditional, and the whole art
 * morph rests on the unsampled key being EXACTLY what it always was: `AlbumArtKeyer` is
 * what makes a list row and a detail screen resolve to one shared bitmap, so if the
 * default key changes, every surface's key changes at once, the morph silently degrades
 * to a cross-fade, and nothing else in the suite notices.
 *
 * The literals below are therefore deliberate, in the same spirit as `MotionTest`'s pinned
 * stagger step: writing them in terms of the production expression would let the very
 * refactor this guards against pass. Dropping the `sample == FULL` short-circuit, or
 * changing the `@1-` separator, must fail here.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned for the same reason as SongDaoTest.
@Config(sdk = [34])
class AlbumArtKeyerTest {

    private val keyer = AlbumArtKeyer()
    private val art = SongArt(songId = 17, uri = "content://17", filePath = null, hasEmbeddedArt = false)

    private fun options(sample: Int?): Options {
        val context: Context = ApplicationProvider.getApplicationContext()
        val parameters = if (sample == null) {
            Parameters.EMPTY
        } else {
            Parameters.Builder().set(ArtDecode.PARAMETER, sample).build()
        }
        return Options(context = context, parameters = parameters)
    }

    /**
     * THE invariant. This exact string is what every existing surface already caches under,
     * so it must survive any future change to the sampling feature.
     */
    @Test fun `a full-size request keys exactly as it always did`() {
        assertEquals("song-art-17", keyer.key(art, options(ArtDecode.FULL)))
    }

    /** A request that never mentions sampling is a full-size request. */
    @Test fun `a request with no sample parameter keys as full size`() {
        assertEquals("song-art-17", keyer.key(art, options(null)))
    }

    /** A sampled decode must land somewhere else, or it can be served to a full-size surface. */
    @Test fun `a sampled request keys separately`() {
        assertEquals("song-art-17@1-32", keyer.key(art, options(32)))
    }

    /**
     * A nonsense divisor must not invent a third namespace: 0 and negatives clamp to FULL,
     * so they share the one shared entry rather than each stranding a cache entry of their own.
     */
    @Test fun `a non-positive sample clamps to full size`() {
        assertEquals("song-art-17", keyer.key(art, options(0)))
        assertEquals("song-art-17", keyer.key(art, options(-4)))
    }

    /** Different songs never collide, sampled or not. */
    @Test fun `the song id still separates entries`() {
        val other = art.copy(songId = 18)
        assertEquals("song-art-18", keyer.key(other, options(ArtDecode.FULL)))
        assertEquals("song-art-18@1-32", keyer.key(other, options(32)))
    }
}
