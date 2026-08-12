// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import android.net.Uri
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        sourceId: String = "test-source",
        externalId: String = "ms-${id + 9000}",
    ) = Song(
        id = id,
        // Stated for the same reason `relativeKey` is: no default exists, so every site says what
        // its identity situation is.
        //
        // *** THE RULE THIS COMMENT USED TO STATE WAS INVERTED BY N0 TASK 6. *** It said the
        // Media3 `mediaId` must be built from `Song.id`. It must now be built from
        // `sourceId:externalId` and must contain the surrogate NOWHERE — a surrogate is unique but
        // a database wipe reassigns it, and a mediaId has to still mean something to a future
        // session-restore path after one.
        //
        // What has NOT changed is why `externalId` is deliberately not `id.toString()`: the two
        // identities must stay tellable apart, and a fixture where they agreed would let a
        // regression that used the wrong one pass. That is now load-bearing in the opposite
        // direction, which is exactly why the fixture keeps the distinction.
        sourceId = sourceId,
        externalId = externalId,
        uri = "content://media/external/audio/media/$id",
        filePath = "/storage/emulated/0/Music/$id.mp3",
        // Stated explicitly, not defaulted: `Song.relativeKey` deliberately has no default
        // value so that every construction site has to say what its key situation is. This
        // fixture models the ordinary case — a file with a full set of MediaStore columns.
        relativeKey = "external_primary:Music/$id.mp3",
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

    // ------------------------------------------------- source-qualified mediaId (N0 Task 6)

    /**
     * The mediaId is the DURABLE identity, not the surrogate.
     *
     * A surrogate is unique but it is reassigned by a database wipe, and this string has to
     * survive one: `PlaybackConnection.publish()` is meant to hydrate a restored session from its
     * media ids, and a v7-style wipe renumbers every row underneath it. `(sourceId, externalId)`
     * is the identity that outlives the table.
     */
    @Test fun `mediaId is the source-qualified external identity`() {
        val item = itemFor(song(sourceId = "local", externalId = "ms-9042"))
        assertEquals("local:ms-9042", item.mediaId)
    }

    /**
     * Two sources may hand out the same source-native id for different tracks — `externalId` is
     * unique only WITHIN a source. Qualifying it is what keeps the mediaId injective, and the
     * encoding is unambiguous only because `SourceRegistry` rejects a `:` in a source id, so the
     * FIRST colon is always the boundary. That guarantee is asserted in `SourceRegistryTest`, in
     * the constructor that enforces it — not here, and not at this call site (GC 10).
     *
     * Asserted as a pair so the failure message IS the collapse.
     */
    @Test fun `two sources sharing an externalId do not collapse into one mediaId`() {
        val a = itemFor(song(sourceId = "alpha", externalId = "7"))
        val b = itemFor(song(sourceId = "beta", externalId = "7"))
        assertEquals("alpha:7" to "beta:7", a.mediaId to b.mediaId)
    }

    /**
     * The surrogate must not leak into the mediaId at all — not as the whole string, not as a
     * third component somebody added "just in case". This is the assertion that would catch a
     * well-meaning `"${'$'}{song.sourceId}:${'$'}{song.externalId}:${'$'}{song.id}"`.
     */
    @Test fun `the surrogate id appears nowhere in the mediaId`() {
        val item = itemFor(song(id = 123L, sourceId = "local", externalId = "ms-9"))
        assertFalse("surrogate leaked into mediaId: ${'$'}{item.mediaId}", item.mediaId.contains("123"))
    }
}
