package com.kaislate.veldtplayer.data.library.db

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongMappersTest {
    private val song = Song(
        id = 42, uri = "content://media/external/audio/media/42", filePath = "/x/y.flac",
        title = "T", artist = "A", album = "Al", albumArtist = "AA", trackNumber = 3,
        discNumber = 1, year = 2020, durationMs = 123456, dateModifiedSec = 999,
        hasEmbeddedArt = true,
    )

    @Test fun domainToEntityToDomain_isIdentity() {
        assertEquals(song, song.toEntity().toDomain())
    }

    @Test fun nullableFields_surviveRoundTrip() {
        val bare = song.copy(filePath = null, albumArtist = null, trackNumber = null,
            discNumber = null, year = null)
        assertEquals(bare, bare.toEntity().toDomain())
    }
}
