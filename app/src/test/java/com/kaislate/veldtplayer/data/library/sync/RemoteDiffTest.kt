// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import com.kaislate.veldtplayer.data.library.db.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit — [RemoteDiff] touches no Android type.
 *
 * The spec: a fetched row is an upsert iff no existing row has its externalId, or the existing
 * row differs in any field OTHER than [SongEntity.id]. `id` is deliberately excluded from the
 * comparison: it is the app-internal surrogate `upsertBySourceKey` assigns, and a fetched row
 * (fresh off the wire) always carries [com.kaislate.veldtplayer.data.library.model.Song.UNSAVED]
 * for it, so comparing it verbatim would classify every row as changed, forever.
 */
class RemoteDiffTest {

    /** One field per property so a fixture can mutate exactly one without touching the rest. */
    private fun row(
        id: Long = 0L,
        sourceId: String = "src",
        externalId: String,
        uri: String = "uri",
        filePath: String? = "fp",
        relativeKey: String? = "rk",
        title: String = "t",
        artist: String = "ar",
        album: String = "al",
        albumArtist: String? = "aa",
        trackNumber: Int? = 1,
        discNumber: Int? = 1,
        year: Int? = 2000,
        durationMs: Long = 1_000L,
        dateModifiedSec: Long = 100L,
        hasEmbeddedArt: Boolean = false,
    ) = SongEntity(
        id = id, sourceId = sourceId, externalId = externalId, uri = uri, filePath = filePath,
        relativeKey = relativeKey, title = title, artist = artist, album = album,
        albumArtist = albumArtist, trackNumber = trackNumber, discNumber = discNumber, year = year,
        durationMs = durationMs, dateModifiedSec = dateModifiedSec, hasEmbeddedArt = hasEmbeddedArt,
    )

    @Test fun `unchanged rows produce no upserts`() {
        val existing = listOf(row(id = 7L, externalId = "e1"))
        val fetched = listOf(row(id = 0L, externalId = "e1"))
        assertEquals(emptyList<SongEntity>(), RemoteDiff.plan(existing, fetched).upserts)
    }

    /**
     * The fetched row lands in [RemoteDiff.Plan.upserts] as-is — with `id = 0`, the sentinel every
     * enumerated row carries. `RemoteDiff` has no business assigning or copying a surrogate; that
     * is `SongDao.upsertBySourceKey`'s job, done by looking the natural key up at write time. This
     * test would fail if `plan` tried to "help" by copying the existing row's id onto the result.
     */
    @Test fun `a changed title is an upsert and carries no id requirement`() {
        val existing = listOf(row(id = 7L, externalId = "e1", title = "old"))
        val fetched = listOf(row(id = 0L, externalId = "e1", title = "new"))
        assertEquals(fetched, RemoteDiff.plan(existing, fetched).upserts)
    }

    @Test fun `a new externalId is an upsert`() {
        val existing = listOf(row(id = 1L, externalId = "already-here"))
        val fetched = listOf(row(id = 0L, externalId = "brand-new"))
        assertEquals(fetched, RemoteDiff.plan(existing, fetched).upserts)
    }

    @Test fun `removed is existing externalIds absent from fetched, in existing order`() {
        val existing = listOf(
            row(id = 1L, externalId = "gone1"),
            row(id = 2L, externalId = "kept"),
            row(id = 3L, externalId = "gone2"),
        )
        val fetched = listOf(row(id = 0L, externalId = "kept"))
        assertEquals(listOf("gone1", "gone2"), RemoteDiff.plan(existing, fetched).removedExternalIds)
    }

    /**
     * TOTAL over fields (Review Focus): [SongEntity] has 16 constructor properties; excluding
     * [SongEntity.id] leaves 15. One case per field below, each pair sharing an externalId unique
     * to that field (so a forgotten field's pair compares EQUAL and its externalId simply never
     * shows up in the result — the failure names exactly which field was skipped) — except the
     * `externalId` case itself, which by construction can only be exercised as a "new id" upsert
     * rather than an equality mismatch, since externalId IS the join key.
     */
    @Test fun `TOTAL - a difference in any field other than id is an upsert`() {
        fun pair(name: String, mutate: (SongEntity) -> SongEntity): Pair<SongEntity, SongEntity> {
            val existing = row(externalId = "field:$name")
            return existing to mutate(existing)
        }

        val cases = listOf(
            pair("sourceId") { it.copy(sourceId = "other-src") },
            pair("externalId") { it.copy(externalId = "field:externalId-new") },
            pair("uri") { it.copy(uri = "other-uri") },
            pair("filePath") { it.copy(filePath = "other-fp") },
            pair("relativeKey") { it.copy(relativeKey = "other-rk") },
            pair("title") { it.copy(title = "other-title") },
            pair("artist") { it.copy(artist = "other-artist") },
            pair("album") { it.copy(album = "other-album") },
            pair("albumArtist") { it.copy(albumArtist = "other-aa") },
            pair("trackNumber") { it.copy(trackNumber = 2) },
            pair("discNumber") { it.copy(discNumber = 2) },
            pair("year") { it.copy(year = 2001) },
            pair("durationMs") { it.copy(durationMs = 2_000L) },
            pair("dateModifiedSec") { it.copy(dateModifiedSec = 200L) },
            pair("hasEmbeddedArt") { it.copy(hasEmbeddedArt = true) },
        )
        assertEquals("expected one case per non-id field", 15, cases.size)

        val existing = cases.map { it.first }
        val fetched = cases.map { it.second }
        val expected = fetched.map { it.externalId }.toSet()

        val upserts = RemoteDiff.plan(existing, fetched).upserts

        assertEquals(expected, upserts.map { it.externalId }.toSet())
    }
}
