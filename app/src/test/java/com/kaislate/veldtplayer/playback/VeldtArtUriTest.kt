// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import com.kaislate.veldtplayer.data.art.SongArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric for a real [Uri]: percent-encoding and query parsing are the entire subject,
 * and the stubbed `android.net.Uri` returns null for all of it.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class VeldtArtUriTest {

    private fun art(
        id: Long = 42L,
        uri: String = "content://media/external/audio/media/42",
        filePath: String? = "/storage/emulated/0/Music/a.mp3",
        hasEmbeddedArt: Boolean = true,
    ) = SongArt(songId = id, uri = uri, filePath = filePath, hasEmbeddedArt = hasEmbeddedArt)

    private fun roundTrip(a: SongArt): SongArt? = VeldtArtUri.parse(VeldtArtUri.of(a))

    @Test fun `every field survives the round trip`() {
        val original = art()
        assertEquals(original, roundTrip(original))
    }

    @Test fun `the uri scheme is not one any other loader can open`() {
        assertEquals(VeldtArtUri.SCHEME, VeldtArtUri.of(art()).scheme)
    }

    /**
     * Two library rows must not become one cache entry. Asserted as a pair, so a collapse
     * shows as `expected:<[..songId=1.., ..songId=2..]> but was:<[..songId=1.., ..songId=1..]>`
     * rather than as a bare inequality that says nothing about what merged.
     */
    @Test fun `two songs differing only in id stay two songs`() {
        val first = art(id = 1L)
        val second = art(id = 2L)
        assertEquals(
            listOf<SongArt?>(first, second),
            listOf(roundTrip(first), roundTrip(second)),
        )
    }

    /**
     * A path containing the query separators must not be re-read as query parameters. The
     * pair is the point: the crafted path carries `&emb=0`, so a naive concatenating encoder
     * would parse it back with `hasEmbeddedArt = false` — i.e. as the OTHER song here.
     */
    @Test fun `a file path containing query syntax does not rewrite the other fields`() {
        val crafted = art(filePath = "/music/rock & roll?x=1&emb=0.mp3", hasEmbeddedArt = true)
        val plain = art(filePath = "/music/plain.mp3", hasEmbeddedArt = false)
        assertEquals(
            listOf<SongArt?>(crafted, plain),
            listOf(roundTrip(crafted), roundTrip(plain)),
        )
    }

    /** "No path at all" and "an empty path" are different rows; they must decode differently. */
    @Test fun `a null path and an empty path stay distinguishable`() {
        val absent = art(filePath = null)
        val empty = art(filePath = "")
        assertEquals(
            listOf<SongArt?>(absent, empty),
            listOf(roundTrip(absent), roundTrip(empty)),
        )
    }

    /** The embedded-art flag decides whether the ladder gets a second rung at all. */
    @Test fun `the embedded art flag survives in both directions`() {
        val withArt = art(hasEmbeddedArt = true)
        val without = art(hasEmbeddedArt = false)
        assertEquals(
            listOf<SongArt?>(withArt, without),
            listOf(roundTrip(withArt), roundTrip(without)),
        )
    }

    /**
     * The track's own audio uri is the input the private scheme exists to reject: handed to
     * a default loader it would download the MP3 and try to decode it as an image.
     */
    @Test fun `the audio uri and the legacy albumart uri are both rejected`() {
        assertNull(VeldtArtUri.parse(Uri.parse("content://media/external/audio/media/42")))
        assertNull(VeldtArtUri.parse(Uri.parse("content://media/external/audio/albumart/7")))
        assertNull(VeldtArtUri.parse(Uri.parse("file:///storage/emulated/0/Music/a.mp3")))
    }

    /** A malformed one of our own is rejected too rather than resolving to song 0. */
    @Test fun `a veldt uri without a numeric id is rejected`() {
        assertNull(VeldtArtUri.parse(Uri.parse("${VeldtArtUri.SCHEME}://song/not-a-number")))
        assertNull(VeldtArtUri.parse(Uri.parse("${VeldtArtUri.SCHEME}://song")))
        assertNull(VeldtArtUri.parse(Uri.parse("${VeldtArtUri.SCHEME}://album/42")))
    }
}
