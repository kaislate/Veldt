// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the notification, the lock screen and Android Auto actually render. Before Task 9
 * the artwork line simply was not here, and nothing could tell: every field these surfaces
 * read was built inside a class that cannot be constructed without a live `MediaController`.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
class SessionMediaItemTest {

    private fun song(
        id: Long = 42L,
        title: String = "Sea Change",
        artist: String = "Beck",
        album: String = "Sea Change",
    ) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = "/storage/emulated/0/Music/$id.mp3",
        title = title,
        artist = artist,
        album = album,
        albumArtist = null,
        trackNumber = 1,
        discNumber = 1,
        year = 2002,
        durationMs = 240_000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = true,
    )

    private fun itemFor(s: Song) = sessionMediaItem(s, s.uri)

    /**
     * **The trap, asserted at the encode end.** Pointing `artworkUri` at the track's own
     * `content://` uri is the fix that looks obvious and does not work: Media3's default
     * loader would fetch the MP3 and hand it to `BitmapFactory`.
     *
     * Asserted as the pair (playback uri, artwork uri) so a collapse reads as the same uri
     * printed twice, which is the mistake itself, rather than as "expected X but was Y".
     */
    @Test fun `the artwork uri is not the audio uri`() {
        val s = song()
        val item = itemFor(s)

        assertEquals(
            listOf(Uri.parse(s.uri), VeldtArtUri.of(s.toSongArt())),
            listOf(item.localConfiguration?.uri, item.mediaMetadata.artworkUri),
        )
    }

    /** And it is present at all — the whole defect was that it was not. */
    @Test fun `every item carries artwork`() {
        assertNotNull(itemFor(song()).mediaMetadata.artworkUri)
    }

    /**
     * Media3 wraps the session's loader in `CacheBitmapLoader`, which caches by uri. Two
     * tracks whose artwork uris compared equal would therefore share one cover — including
     * two tracks with identical tags, which is what this pair uses.
     */
    @Test fun `two tracks with identical tags still get two artwork uris`() {
        val first = song(id = 1L)
        val second = song(id = 2L)
        assertEquals(
            listOf(VeldtArtUri.of(first.toSongArt()), VeldtArtUri.of(second.toSongArt())),
            listOf(itemFor(first), itemFor(second)).map { it.mediaMetadata.artworkUri },
        )
    }

    /**
     * The rest of the metadata still goes through DisplayNames. The notification and the
     * lock screen are the one place the user cannot correct a tag, so MediaStore's
     * `<unknown>` sentinel must not survive to them — and neither must an empty title.
     */
    @Test fun `unknown tags are named rather than passed through raw`() {
        val item = itemFor(song(title = "  ", artist = "<unknown>", album = "<unknown>"))
        assertEquals(
            listOf(
                DisplayNames.UNKNOWN_TITLE,
                DisplayNames.UNKNOWN_ARTIST,
                DisplayNames.UNKNOWN_ALBUM,
            ),
            listOf(
                item.mediaMetadata.title,
                item.mediaMetadata.artist,
                item.mediaMetadata.albumTitle,
            ).map { it?.toString() },
        )
    }

    @Test fun `the media id is the song id so the session can be mapped back to the library`() {
        assertEquals("42", itemFor(song()).mediaId)
    }
}
