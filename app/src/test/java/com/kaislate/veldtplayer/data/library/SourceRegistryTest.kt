// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The registry is where "which source owns this?" stops being a hardcoded answer.
 *
 * Pure JVM: [LibrarySource] is framework-free by contract, so the fakes below are constructed
 * directly and no Robolectric runtime is needed.
 *
 * The character bans are tested here rather than at the call sites that depend on them, because
 * they are what make two *encodings* injective — the `sourceId:externalId` mediaId (Task 6) and
 * the future `veldt://` path (design spec §4.4). A ban enforced at a call site is a ban the next
 * call site forgets.
 */
class SourceRegistryTest {

    private fun source(sourceId: String) = object : LibrarySource {
        override val id = sourceId
        override suspend fun listSongs() = emptyList<Song>()
        override suspend fun listAlbums() = emptyList<Album>()
        override suspend fun listArtists() = emptyList<Artist>()
        override suspend fun search(query: String) = emptyList<Song>()
        override fun resolvePlayableUri(song: Song) = song.uri
        override fun stableKey(song: Song) = song.uri
    }

    @Test
    fun `byId returns the source registered under that id — and not the other one`() {
        val alpha = source("alpha")
        val beta = source("beta")
        val registry = SourceRegistry(setOf(alpha, beta))
        // Asserted as a pair, so the failure message IS the collapse: a registry that answers
        // "the" source for every id passes each half of this separately.
        assertEquals(alpha to beta, registry.byId("alpha") to registry.byId("beta"))
    }

    @Test
    fun `an unknown id is null from byId and names itself in require's error`() {
        val registry = SourceRegistry(setOf(source("alpha")))
        assertNull(registry.byId("ghost"))
        val e = assertThrows(IllegalStateException::class.java) { registry.require("ghost") }
        assertEquals("""no LibrarySource registered for "ghost"""", e.message)
    }

    @Test
    fun `duplicate source ids are rejected at construction`() {
        // Two DISTINCT instances claiming one id — a Set cannot dedupe them, so associateBy
        // would silently keep whichever came last and the other source would be unreachable.
        assertThrows(IllegalArgumentException::class.java) {
            SourceRegistry(setOf(source("dup"), source("dup")))
        }
    }

    @Test
    fun `a source id containing a colon or slash or nothing is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SourceRegistry(setOf(source("a:b"))) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegistry(setOf(source("a/b"))) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegistry(setOf(source(""))) }
        assertThrows(IllegalArgumentException::class.java) { SourceRegistry(setOf(source("  "))) }
    }

    @Test
    fun `a legal id adjacent to the banned ones is accepted`() {
        // The negative control for the test above: without this, tightening the require() to
        // reject everything would pass the ban tests and break the app.
        val registry = SourceRegistry(setOf(source("sub-sonic_1.2"), source("local")))
        assertEquals(
            listOf("sub-sonic_1.2", "local"),
            listOf(registry.require("sub-sonic_1.2").id, registry.require("local").id),
        )
    }

    @Test
    fun `all exposes every registered source`() {
        val registry = SourceRegistry(setOf(source("alpha"), source("beta")))
        assertEquals(setOf("alpha", "beta"), registry.all.map { it.id }.toSet())
    }
}
