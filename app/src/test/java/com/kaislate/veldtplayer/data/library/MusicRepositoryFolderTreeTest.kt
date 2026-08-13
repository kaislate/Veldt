// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MusicRepository.folderTree()` — the call site global constraint 14 is about.
 *
 * Until this existed the flow was asserted by nothing: returning `emptyList()` from it, or dropping
 * its `distinctUntilChanged()`, both left the whole suite green, so "derived once per emission" was
 * a claim about code shape rather than behaviour.
 *
 * **Emission count is derivation count here, and that is exact rather than approximate.** `map`
 * invokes its transform once per upstream emission and emits the result, so counting what
 * `folderTree()` produces counts what `FolderTree.build` was asked to do. No spy is needed and none
 * would be more truthful.
 *
 * Robolectric only because [MusicRepository]'s constructor takes a `Context`; nothing in this flow
 * touches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositoryFolderTreeTest {

    /**
     * Emits a fixed script of song lists.
     *
     * **Not a `MutableStateFlow`, deliberately.** `StateFlow` conflates equal values on its own, so
     * the de-duplication test below would pass against a repository with no `distinctUntilChanged()`
     * at all — the fake would be asserting its own behaviour. A finite `asFlow()` re-emits whatever
     * it is given, which is what makes that test able to fail.
     */
    private class FakeSongDao(private val script: List<List<SongEntity>>) : SongDao {
        override fun observeAllSongs(): Flow<List<SongEntity>> = script.asFlow()
        override suspend fun findIdBySourceKey(sourceId: String, externalId: String): Long? = null
        override suspend fun insertReplacing(row: SongEntity) = Unit
        override suspend fun getAllSongs(): List<SongEntity> = emptyList()
        override suspend fun getSongsByAlbum(album: String): List<SongEntity> = emptyList()
        override suspend fun getSongsByArtist(artist: String): List<SongEntity> = emptyList()
        override suspend fun search(pattern: String): List<SongEntity> = emptyList()
        override fun observeSearch(pattern: String): Flow<List<SongEntity>> = emptyFlow()
        override suspend fun getIndex(sourceId: String): List<IndexEntry> = emptyList()
        override suspend fun deleteByExternalIds(sourceId: String, externalIds: List<String>) = Unit
        override suspend fun clear() = Unit
    }

    private fun repo(vararg script: List<SongEntity>) = MusicRepository(
        FakeSongDao(script.toList()),
        SourceRegistry(emptySet()),
        ApplicationProvider.getApplicationContext(),
    )

    /** [id] is explicit so two separately-built lists can be EQUAL — see the de-duplication test. */
    private fun row(id: Long, relativeKey: String) = SongEntity(
        id = id, sourceId = "test", externalId = "e$id", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    @Test fun `the tree is derived from the songs flow`() = runTest {
        val tree = repo(
            listOf(
                row(1, "external_primary:Music/Beck/a.mp3"),
                row(2, "external_primary:Music/Radiohead/b.mp3"),
            )
        ).folderTree().toList().single()
        // Asserted level by level rather than by walking with single(), so a flow that emits an
        // empty tree fails on the VALUE instead of throwing out of the navigation.
        assertEquals(
            "folderTree did not derive the tree from the song list",
            listOf(listOf("external_primary"), listOf("Music"), listOf("Beck", "Radiohead")),
            listOf(
                tree.map { it.name },
                tree.flatMap { it.children }.map { it.name },
                tree.flatMap { it.children }.flatMap { it.children }.map { it.name },
            ),
        )
    }

    @Test fun `an identical re-emission does not re-derive the tree`() = runTest {
        // Two separately-constructed lists that are EQUAL but not the same instance, because that
        // is what Room does — a fresh list per emission. An identity check would pass here while
        // rebuilding the tree on every batch on a device.
        val first = listOf(row(1, "external_primary:Music/a.mp3"))
        val second = listOf(row(1, "external_primary:Music/a.mp3"))
        assertEquals(
            "an identical re-emission rebuilt the whole tree — distinctUntilChanged is missing, or is downstream of the map",
            1,
            repo(first, second).folderTree().toList().size,
        )
    }

    /**
     * The other half, and the honest one: a CHANGED emission does re-derive.
     *
     * This is what stops `distinctUntilChanged()` from being read as a bound on scan cost. The two
     * lists here are the same LENGTH and differ only in content, which is what makes the test able
     * to fail — a de-duplication keyed on something cheap like `size` would swallow the second
     * emission and is exactly the wrong fix for the burst this does not solve. During a real first
     * scan every batch changes the row set, so every batch rebuilds; the bound on that is the
     * ViewModel's `stateIn`, not anything here.
     */
    @Test fun `a changed emission DOES re-derive`() = runTest {
        val first = listOf(row(1, "external_primary:Music/a.mp3"))
        val second = listOf(row(1, "external_primary:Music/b.mp3"))
        assertEquals(
            "a genuinely changed song list was suppressed — the tree would go stale",
            2,
            repo(first, second).folderTree().toList().size,
        )
    }
}
