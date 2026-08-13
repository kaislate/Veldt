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
 * **These tests assert what each emission CONTAINS, never how many arrived** (global constraint 10).
 * That is not stylistic. A `map` whose transform memoizes — building once and returning the same
 * tree forever — emits exactly the right number of times while serving a permanently stale tree, so
 * a count-based suite calls it correct. Two of these tests previously counted, and that mutant went
 * green against all three of them.
 *
 * The consequence is that **emission count is not a reliable proxy for derivation count**, and no
 * test here claims otherwise. What the assertions establish is narrower than "the tree is right":
 * `the tree is derived from the songs flow` pins the folder STRUCTURE by name, while the two
 * sequence tests pin which SONGS each emission carries, in tree order — [contents] walks songs and
 * does not compare shape, so two differently-shaped trees holding the same songs in the same order
 * would satisfy them. Whether `FolderTree.build` ran once or twice behind a given emission is a
 * third thing, and that one genuinely is unobservable without injecting a counting builder, which
 * would distort the production shape to make a test possible.
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

    /**
     * De-duplication, asserted as the sequence of tree CONTENTS rather than as a count.
     *
     * **Deliberately not pinned: `distinctUntilChanged()` moved DOWNSTREAM of the `map`.** This
     * fixture does not exclude it — with these two song lists the downstream form emits the same
     * single tree — but that is a property of the fixture, not an impossibility. Downstream, the
     * comparison is between two `List<FolderNode>`, and two DIFFERENT song lists can build EQUAL
     * trees, because the tree groups by folder and erases the global ordering `songs()` imposes.
     * `[Music/x, Other/y, Music/z]` then `[Music/x, Music/z, Other/y]` are different lists with
     * equal trees: production emits twice, the downstream form once. A content assertion catches
     * that — no spy, no production seam.
     *
     * **It is left uncovered on purpose.** Such a test would pin "an equal tree is re-emitted",
     * which is not a property we want to guarantee: Task 5's `stateIn` conflates a repeated equal
     * value away regardless, so the test would freeze an incidental artefact of operator order into
     * the contract. The placement rationale lives in `MusicRepository.folderTree()`'s KDoc and is
     * upheld by review. Weighed and declined, not missed.
     */
    @Test fun `an identical re-emission does not re-derive the tree`() = runTest {
        // Two separately-constructed lists that are EQUAL but not the same instance, because that
        // is what Room does — a fresh list per emission. An identity check would pass here while
        // rebuilding the tree on every batch on a device.
        val first = listOf(row(1, "external_primary:Music/a.mp3"))
        val second = listOf(row(1, "external_primary:Music/a.mp3"))
        assertEquals(
            "an identical re-emission produced a second one — distinctUntilChanged is missing",
            listOf(listOf("external_primary:Music/a.mp3")),
            repo(first, second).folderTree().toList().map { contents(it) },
        )
    }

    /**
     * The other half, and the honest one: a CHANGED emission carries the CHANGED tree.
     *
     * Asserting the contents rather than the arrival count is what makes this test able to fail at
     * all. A `map` that memoizes — building once, then handing back the same tree forever — emits
     * the right number of times while the folder tab silently never updates after a scan; against a
     * count this test went green while its own message read "the tree would go stale".
     *
     * The two lists are also the same LENGTH and differ only in content, so a de-duplication
     * cheapened to compare `size` swallows the second emission — the wrong fix for a scan burst that
     * `distinctUntilChanged` does not bound in the first place. During a real first scan every batch
     * changes the row set, so every batch rebuilds; the bound on that is the ViewModel's `stateIn`,
     * not anything here.
     */
    @Test fun `a changed emission carries the changed tree`() = runTest {
        val first = listOf(row(1, "external_primary:Music/a.mp3"))
        val second = listOf(row(1, "external_primary:Music/b.mp3"))
        assertEquals(
            "the second emission did not carry the changed songs — suppressed, or a memoized tree",
            listOf(
                listOf("external_primary:Music/a.mp3"),
                listOf("external_primary:Music/b.mp3"),
            ),
            repo(first, second).folderTree().toList().map { contents(it) },
        )
    }

    /** Every song's location, in tree order — what an emission CONTAINS, never how many arrived. */
    private fun contents(tree: List<FolderNode>): List<String> =
        tree.flatMap { node -> node.songs.map { it.relativeKey.orEmpty() } + contents(node.children) }
}
