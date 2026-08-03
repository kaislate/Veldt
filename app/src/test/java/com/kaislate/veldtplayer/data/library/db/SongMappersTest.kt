// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongMappersTest {
    private val song = Song(
        id = 42, uri = "content://media/external/audio/media/42", filePath = "/x/y.flac",
        relativeKey = "Music/x/y.flac",
        title = "T", artist = "A", album = "Al", albumArtist = "AA", trackNumber = 3,
        discNumber = 1, year = 2020, durationMs = 123456, dateModifiedSec = 999,
        hasEmbeddedArt = true,
    )

    @Test fun domainToEntityToDomain_isIdentity() {
        assertEquals(song, song.toEntity().toDomain())
    }

    @Test fun nullableFields_surviveRoundTrip() {
        val bare = song.copy(filePath = null, relativeKey = null, albumArtist = null,
            trackNumber = null, discNumber = null, year = null)
        assertEquals(bare, bare.toEntity().toDomain())
    }

    /**
     * The identity round-trip above would still pass if [SongEntity.relativeKey] were dropped and
     * both mapper directions consistently ignored it — the field would simply never be observed.
     * Assert the column carries a distinct value through, so the playlist key cannot be silently
     * lost between the scanner and the `songs` table.
     */
    @Test fun relativeKey_isCarriedThroughBothDirections() {
        assertEquals("Music/x/y.flac", song.toEntity().relativeKey)
        assertEquals("Music/x/y.flac", song.toEntity().toDomain().relativeKey)
        assertEquals(null, song.copy(relativeKey = null).toEntity().toDomain().relativeKey)
    }
}
