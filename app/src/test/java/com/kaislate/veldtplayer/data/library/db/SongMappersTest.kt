// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Test

class SongMappersTest {
    private val song = Song(
        id = 42,
        sourceId = "test-source",
        // Global Constraint 14: NOT "42". The mapper carries two identity fields that both look
        // like the row's id; a fixture where `externalId` equalled `id.toString()` would map
        // identically whether or not the mapper confused them.
        externalId = "ms-9042",
        uri = "content://media/external/audio/media/42", filePath = "/x/y.flac",
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

    /**
     * The same argument as [relativeKey_isCarriedThroughBothDirections], for the source dimension:
     * `domainToEntityToDomain_isIdentity` would still pass if BOTH directions consistently dropped
     * `sourceId`/`externalId`, or if both consistently derived `externalId` from `id`. Assert the
     * stored columns directly, at the halfway point where a drop is visible.
     *
     * The three assertions are the three distinct collapses this phase exists to prevent:
     * `externalId` into `id`, `sourceId` into a hardcoded `"local"`, and either into the other.
     */
    @Test fun `the source dimension survives both directions without collapsing into the id`() {
        val entity = song.toEntity()
        assertEquals("test-source", entity.sourceId)
        assertEquals("ms-9042", entity.externalId)
        assertEquals(42L, entity.id)
        val back = entity.toDomain()
        assertEquals("test-source", back.sourceId)
        assertEquals("ms-9042", back.externalId)
    }

    /**
     * Two songs differing ONLY in `sourceId` — the same server-native id under two backends — must
     * not map to the same entity. Asserted as a pair so the failure message IS the merge: a mapper
     * that dropped `sourceId` would produce two equal entities here and nothing else in this file
     * would notice.
     */
    @Test fun `two sources sharing one externalId do not map to one entity`() {
        val alpha = song.copy(sourceId = "alpha", externalId = "shared-1")
        val beta = song.copy(sourceId = "beta", externalId = "shared-1")
        assertEquals(
            listOf("alpha" to "shared-1", "beta" to "shared-1"),
            listOf(alpha, beta).map { it.toEntity() }.map { it.sourceId to it.externalId },
        )
    }
}
