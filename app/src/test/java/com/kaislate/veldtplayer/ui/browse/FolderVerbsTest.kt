// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.kaislate.veldtplayer.data.library.FolderNode
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.VolumeNames
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.playback.PlaybackConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * The folder verbs, asserted on the queue that actually reached [PlaybackConnection].
 *
 * **Not on the return value of a pure function.** `FolderSort.deepFlatten` has its own tests; what
 * has never been pinned is that a BUTTON reaches it, with the right scope, over the right node, in
 * the sort order the user chose. [PlaybackConnection.playFrom] sets `_queue.value` before it needs
 * a `MediaController`, so under Robolectric the queue is observable end-to-end even though nothing
 * below it is — which is exactly the seam worth asserting at.
 *
 * **What is NOT observable here, stated so nobody reads a green run as covering it.** The plan's
 * `startIndex` is handed to `MediaController.setMediaItems` and never published, so no assertion in
 * this file can see it; `QueueBuilderTest` pins the arithmetic and this file pins that the queue the
 * index addresses is the list the user was looking at. Re-check with:
 * `./gradlew.bat --offline testDebugUnitTest --tests "*QueueBuilderTest*"`.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the sibling suites are.
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FolderVerbsTest {

    /**
     * Emits one fixed song list.
     *
     * **Not a `MutableStateFlow`**, for the reason `MusicRepositoryFolderTreeTest` records and
     * `FolderViewModelTest` repeats: a `StateFlow` conflates equal values on its own, so a fake
     * built on one supplies conflation the production code may not have.
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

    private lateinit var context: Context
    private lateinit var settings: SettingsRepository
    private lateinit var connection: PlaybackConnection
    private var vm: FolderViewModel? = null
    private var nextId = 1L

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        // MusicRepository.scanning() reads WorkManager; nothing here enqueues work, so it reports
        // false throughout. Initialised anyway because constructing the repository requires it.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        settings = SettingsRepository(context)
        // One DataStore is shared by every test method in this JVM, so a sort written by one test
        // would otherwise decide another's track order. See SettingsRepositoryTest.
        runBlocking { settings.clearForTest() }
    }

    @After fun tearDown() {
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------------- the fixture

    private fun row(relativeKey: String) = SongEntity(
        id = nextId++, sourceId = "test", externalId = "e$nextId", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    /**
     * `Album/` with two direct tracks, three discs, and a `Bonus/` folder inside the FIRST disc.
     *
     * Every part of the shape is doing work, and each excludes a different wrong walk:
     *
     * - **`Bonus/` inside `Disc 1/`** makes depth-first distinguishable from breadth-first. Without
     *   a grandchild the two orders are identical on a two-level tree and the ordering assertion
     *   would pass under either.
     * - **`Disc 10/`** makes [com.kaislate.veldtplayer.data.library.FolderSort.NATURAL] visible: a
     *   lexicographic walk yields `Disc 1`, `Disc 10`, `Disc 2`.
     * - **The insertion order is deliberately wrong in both dimensions** — the discs go in 10, 2, 1
     *   and the direct tracks go 05 before 01 — so `node.children` raw and `node.songs` raw are
     *   both distinguishable wrong answers rather than accidentally correct ones.
     * - **`Other/`** exists only so root elision stops at `Music` rather than folding all the way
     *   down to `Album`, which would make `Album` the tab root and hide the nesting under test.
     *
     * The file names are also chosen so a GLOBAL sort of the whole subtree by file name differs
     * from the depth-first concatenation: globally, `01 d1a.mp3` sorts between `01 a.mp3` and
     * `05 c.mp3`, which interleaves the discs — the exact failure the folder view exists to repair.
     */
    private fun albumTree(): FolderViewModel = viewModel(
        row("external_primary:Music/Other/z.mp3"),
        row("external_primary:Music/Album/Disc 10/01 d10a.mp3"),
        row("external_primary:Music/Album/Disc 2/01 d2a.mp3"),
        row("external_primary:Music/Album/Disc 1/02 d1b.mp3"),
        row("external_primary:Music/Album/Disc 1/01 d1a.mp3"),
        row("external_primary:Music/Album/Disc 1/Bonus/01 bonus.mp3"),
        row("external_primary:Music/Album/05 c.mp3"),
        row("external_primary:Music/Album/01 a.mp3"),
    )

    private fun viewModel(vararg rows: SongEntity): FolderViewModel {
        val repo = MusicRepository(
            FakeSongDao(listOf(rows.toList())),
            SourceRegistry(emptySet()),
            context,
        )
        connection = PlaybackConnection(context, repo)
        return FolderViewModel(
            repo = repo,
            settings = settings,
            volumeNames = VolumeNames(context),
            connection = connection,
        ).also { vm = it }
    }

    /** The first state carrying a derived tree. The seed has none, and `scanning` starts true. */
    private suspend fun FolderViewModel.settled(): FolderUiState =
        state.first { it.roots.isNotEmpty() }

    private companion object {
        const val ALBUM = "external_primary:Music/Album"
        const val DISC_2 = "external_primary:Music/Album/Disc 2"
    }

    /**
     * The queue as the player received it, named by file rather than by id.
     *
     * A song with no location is named rather than dropped or nulled: every row in this fixture has
     * one, so the placeholder appearing at all is itself a failure the expected list will show.
     */
    private fun queuedNames(): List<String> = namesOf(connection.queue.value)

    private fun namesOf(songs: List<Song>): List<String> =
        songs.map { (it.relativeKey ?: "«no location»").substringAfterLast('/') }

    /**
     * The node a key names, or a failure that says the FIXTURE broke rather than the code.
     *
     * A test that quietly did nothing because its key stopped resolving would assert an empty queue
     * against an empty queue and pass.
     */
    private fun node(state: FolderUiState, key: String): FolderNode =
        vm!!.listing(state, key).node
            ?: throw AssertionError("the fixture no longer contains $key")

    // ------------------------------------------------------------------------------ the two scopes

    /**
     * **The ordering claim, as an exact ordered list of file names.**
     *
     * `Album/`'s own tracks, then ALL of `Disc 1/` — including its `Bonus/` grandchild — then
     * `Disc 2/`, then `Disc 10/`. Three wrong walks each produce a different visible list here:
     * breadth-first moves `01 bonus.mp3` to the end, a lexicographic child order puts `Disc 10`
     * second, and a global file-name sort of the subtree interleaves the discs.
     *
     * Asserted on `connection.queue.value` — the list the player was actually handed — so the claim
     * is about the BUTTON, not about `FolderSort.deepFlatten`, which has its own tests.
     */
    @Test fun `deep play queues this folder then each subfolder, depth-first`() = runTest {
        val vm = albumTree()
        val state = vm.settled()

        vm.playFolderDeep(state, node(state, ALBUM))

        assertEquals(
            "the deep queue is not depth-first pre-order in natural folder order — a " +
                "breadth-first walk moves the Bonus track to the end, a lexicographic child " +
                "order puts Disc 10 second, and a global filename sort interleaves the discs",
            listOf(
                "01 a.mp3", "05 c.mp3",
                "01 d1a.mp3", "02 d1b.mp3", "01 bonus.mp3",
                "01 d2a.mp3",
                "01 d10a.mp3",
            ),
            queuedNames(),
        )
    }

    /** The secondary verb stops at the folder the user is looking at. */
    @Test fun `shallow play queues only this folder's own tracks`() = runTest {
        val vm = albumTree()
        val state = vm.settled()

        vm.playFolderShallow(state, node(state, ALBUM))

        assertEquals(
            "the shallow queue is not the folder's own tracks in file-name order",
            listOf("01 a.mp3", "05 c.mp3"),
            queuedNames(),
        )
    }

    /**
     * **The two scopes are different lists, asserted as one pair.**
     *
     * A pair and not two assertions, because the failure worth naming is the COLLAPSE — one verb
     * quietly delegating to the other — and either direction leaves one of two separate assertions
     * green while the other fails with a message about walk order, which points at the wrong thing.
     *
     * **Driven through the two VERBS, not through `folderTracks`.** That is not a stylistic choice:
     * `playFolderDeep` delegating to `playFolderShallow` — the plan's own negative control — leaves
     * `folderTracks` untouched, so a `folderTracks`-level version of this test stays GREEN under
     * exactly the mutation it is named for. Executed, 2026-08-13: at the primitive it survived and
     * only the two ordering tests went red; through the verbs it fails with the message below.
     */
    @Test fun `the two scopes produce different lists`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val album = node(state, ALBUM)

        vm.playFolderShallow(state, album)
        val shallow = queuedNames()
        vm.playFolderDeep(state, album)
        val deep = queuedNames()

        assertEquals(
            "the two folder scopes collapsed into one — 'this folder' and 'with subfolders' " +
                "produced the same list, so one of the two verbs is a duplicate of the other",
            listOf(
                listOf("01 a.mp3", "05 c.mp3"),
                listOf(
                    "01 a.mp3", "05 c.mp3",
                    "01 d1a.mp3", "02 d1b.mp3", "01 bonus.mp3",
                    "01 d2a.mp3",
                    "01 d10a.mp3",
                ),
            ),
            listOf(shallow, deep),
        )
    }

    /**
     * A leaf folder's two scopes ARE the same list — which is why the menu draws only one item for
     * it, and why the test above needs a folder with children to say anything.
     *
     * **Both sides are literals.** Comparing `namesOf(shallow)` against `namesOf(deep)` with no
     * literal anywhere was vacuous: executed 2026-08-13, with `folderTracks` returning
     * `emptyList()` for both scopes, eight of this file's tests reddened and this one stayed GREEN
     * asserting `[] == []`. An expected value derived from production cannot disagree with it.
     */
    @Test fun `a leaf folder's two scopes are the same list`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val disc2 = node(state, DISC_2)

        assertEquals(
            "a leaf folder's scopes disagree, or one of them stopped producing the leaf's track " +
                "at all — 'this folder only' would then be a menu item that does something " +
                "different from the primary verb for no visible reason",
            listOf(listOf("01 d2a.mp3"), listOf("01 d2a.mp3")),
            listOf(
                namesOf(vm.folderTracks(state, disc2, FolderScope.THIS_FOLDER)),
                namesOf(vm.folderTracks(state, disc2, FolderScope.WITH_SUBFOLDERS)),
            ),
        )
    }

    // ---------------------------------------------------------------------------- tapping a track

    /**
     * A track tap queues [FolderListing.tracks] — the DIRECT list, as the screen drew it.
     *
     * `listing.tracks` is a third derivation, separate from either scope of [FolderScope]: `listing`
     * calls `FolderSort.tracks(node.songs, …)` itself. So this is not a duplicate of the shallow
     * play test; it pins the list the track rows are actually built from.
     *
     * **What this test does NOT cover, and a correction.** Until 2026-08-13 it also asserted
     * `queuedNames()[1] == "05 c.mp3"`, justified by a claim that a queue built from the DEEP list
     * would hold a different track at index 1. **That claim is false.** `deepFlatten` is depth-first
     * PRE-ORDER, so the deep list begins with the entire direct list — here `01 a.mp3`, `05 c.mp3`,
     * exactly as the deep test's own expected value at the top of this file shows. Index 1 is the
     * same track under both, and no index inside the direct list can ever tell the two apart; only
     * the whole list can, which is what the one remaining element does. The dropped element was
     * `expected[0][1]` restated and could not fail on its own.
     *
     * The tapped `startIndex` is not asserted anywhere in this file because it is not observable:
     * it is handed to `MediaController.setMediaItems` and never published. Executed — `play(tracks,
     * index)` → `play(tracks, 0)` survives the whole suite. That is a device check, not a covered
     * one. Re-check with:
     * `./gradlew.bat --offline testDebugUnitTest --tests "*QueueBuilderTest*"` for the arithmetic.
     */
    @Test fun `tapping a track queues the listing's own direct track list`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val listing = vm.listing(state, ALBUM)

        vm.play(listing.tracks, 1)

        assertEquals(
            "the queue that reached the player is not the direct list the screen was showing",
            listOf("01 a.mp3", "05 c.mp3"),
            queuedNames(),
        )
    }

    /** An index outside the list plays nothing rather than clamping to some other track. */
    @Test fun `a tap outside the list queues nothing`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val listing = vm.listing(state, ALBUM)

        vm.play(listing.tracks, 99)

        assertEquals(
            "an out-of-range tap reached the player anyway",
            emptyList<String>(),
            queuedNames(),
        )
        // The complement, so the assertion above is not satisfied by a verb that queues NOTHING
        // ever: the same list at an index inside it does reach the player.
        vm.play(listing.tracks, 0)
        assertEquals(
            "an in-range tap stopped reaching the player",
            listOf("01 a.mp3", "05 c.mp3"),
            queuedNames(),
        )
    }

    // ---------------------------------------------------------------------------------- the queue

    /**
     * Append puts the folder AFTER what is already queued, and says how many tracks that was.
     *
     * Two properties in one assertion, because each is a different way for the same call to lie:
     *
     * - **The append EXTENDS.** The seed queue is still at the head. Replacing it would be
     *   `playFrom` under another name, and it is a one-character mistake to make.
     * - **The SCOPE is honoured.** The appended folder is `Album/`, which has subfolders — so a
     *   `WITH_SUBFOLDERS` append that quietly used the direct list appends 2 tracks instead of 7
     *   and is visible here. **This is why the appended folder is not a leaf.** Until 2026-08-13
     *   this test appended `Disc 2/`, where the two scopes are by definition the same list, and
     *   hardcoding `scope` to `THIS_FOLDER` inside `addFolderToQueue` survived the entire 670-test
     *   suite — five-sevenths of a record silently dropped under a confident count. Executed as the
     *   control for this rewrite.
     *
     * **What this test canNOT see, corrected after executing it:** whether the count in the message
     * came from the queue or from `FolderNode.deepSongCount`. On a `WITH_SUBFOLDERS` append those
     * two are equal **by construction** — `deepFlatten` yields exactly `deepSongCount` tracks — so
     * the `deepSongCount` mutant leaves this test green. It is caught by the SHALLOW append below
     * and by nothing else, which is the one place the two numbers disagree (2 against 7). Executed
     * 2026-08-13; an earlier draft of this KDoc claimed the count was pinned here too, and it was
     * not. Re-check by replacing `tracks.size` with `node.deepSongCount` in `addFolderToQueue` and
     * running `--tests "*FolderVerbsTest*"`: exactly one test reddens, and it is not this one.
     *
     * `01 d2a.mp3` appears TWICE on purpose: the seed queue is `Disc 2/` and the deep append
     * contains it again. `QueueBuilder.append` deliberately does not de-duplicate — queuing a track
     * that is already further down the queue is a thing people do on purpose — so a dedupe
     * introduced there would shorten this list and be caught rather than silently changing what
     * "add to queue" means.
     */
    @Test fun `appending a folder keeps the queue and reports the tracks it added`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { messages += it }
        }

        vm.playFolderShallow(state, node(state, DISC_2))
        vm.addFolderToQueue(state, node(state, ALBUM), FolderScope.WITH_SUBFOLDERS)
        advanceUntilIdle()

        assertEquals(
            "the append replaced the queue instead of extending it, ignored its scope and " +
                "appended only the direct list, or quoted a number that did not come from the " +
                "list it handed to the player",
            listOf<Any?>(
                listOf(
                    "01 d2a.mp3",
                    "01 a.mp3", "05 c.mp3",
                    "01 d1a.mp3", "02 d1b.mp3", "01 bonus.mp3",
                    "01 d2a.mp3",
                    "01 d10a.mp3",
                ),
                listOf("Added 7 tracks to the queue"),
            ),
            listOf<Any?>(queuedNames(), messages),
        )
    }

    /**
     * The SHALLOW append is the **only** place a count taken from the node rather than from the
     * queue shows up: `Album`'s subtree holds seven tracks and its direct list holds two, and every
     * other append in this file is deep, where `tracks.size` and `FolderNode.deepSongCount` are
     * equal by construction. Executed — `tracks.size` → `node.deepSongCount` reddens this test and
     * no other. Do not "simplify" this method into the deep append above; that would delete the
     * only coverage the count has.
     */
    @Test fun `a shallow append reports the direct list, not the subtree`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val messages = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.messages.collect { messages += it }
        }

        vm.addFolderToQueue(state, node(state, ALBUM), FolderScope.THIS_FOLDER)
        advanceUntilIdle()

        assertEquals(
            "the shallow append queued or reported the subtree — a count read from " +
                "FolderNode.deepSongCount rather than from the list handed to the player",
            listOf<Any?>(listOf("01 a.mp3", "05 c.mp3"), listOf("Added 2 tracks to the queue")),
            listOf<Any?>(queuedNames(), messages),
        )
    }

    // ------------------------------------------------------------------------------- the playlist

    /**
     * A folder add is a SNAPSHOT of a scope, named by the label on screen.
     *
     * The subject is passed in rather than derived, which is what lets a top-level row be named
     * after its VOLUME; asserted with a label that is deliberately NOT the node's own name, so a
     * `node.name` shortcut inside `folderAddition` is visible.
     */
    @Test fun `a folder add carries the scope's tracks under the label the screen drew`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val album = node(state, ALBUM)

        val deep = vm.folderAddition(state, album, "SanDisk Ultra", FolderScope.WITH_SUBFOLDERS)
        val shallow = vm.folderAddition(state, album, "SanDisk Ultra", FolderScope.THIS_FOLDER)

        assertEquals(
            "the addition's subject was derived from the node instead of taken from the label " +
                "the row drew, or the two scopes contributed the same tracks",
            listOf<Any?>(
                "SanDisk Ultra",
                listOf(
                    "01 a.mp3", "05 c.mp3",
                    "01 d1a.mp3", "02 d1b.mp3", "01 bonus.mp3",
                    "01 d2a.mp3",
                    "01 d10a.mp3",
                ),
                listOf("01 a.mp3", "05 c.mp3"),
            ),
            listOf<Any?>(
                deep.subject,
                namesOf(deep.songs),
                namesOf(shallow.songs),
            ),
        )
        assertEquals(
            "the sheet does not name the folder and count its tracks",
            "Add 7 tracks from “SanDisk Ultra”",
            PlaylistAdditions.sheetTitle(deep),
        )
    }

    // ---------------------------------------------------------------------------------- the sort

    /**
     * The stored sort preference reaches the DEEP flatten, not just the listed tracks.
     *
     * `deepFlatten` applies the comparator once per folder, so this is a separate application of
     * the preference rather than a consequence of the shallow one: `deepFlatten` passing a
     * hardcoded `TrackSort.FILENAME` / `descending = false` down its recursion would leave the
     * screen's own track list correctly reversed and the deep queue silently ascending.
     *
     * **The FOLDER order is deliberately unaffected** — `FolderSort.folders` takes no direction and
     * the preference is a TRACK sort, which is what the menu says ("Reverse order", under the track
     * orders). So the discs stay `Disc 1`, `Disc 2`, `Disc 10` while the tracks inside each of them
     * flip. That is asserted rather than assumed, because "reverse the whole flattened list" is the
     * obvious wrong implementation and it produces a visibly different order.
     */
    @Test fun `the stored sort reaches the deep queue too`() = runTest {
        val vm = albumTree()
        vm.settled()
        vm.setDescending(true)

        val state = vm.state.first { it.descending }
        vm.playFolderDeep(state, node(state, ALBUM))

        assertEquals(
            "the reverse-order preference never reached the deep flatten, or it reversed the " +
                "whole flattened list instead of each folder's own tracks",
            listOf(
                "05 c.mp3", "01 a.mp3",
                "02 d1b.mp3", "01 d1a.mp3", "01 bonus.mp3",
                "01 d2a.mp3",
                "01 d10a.mp3",
            ),
            queuedNames(),
        )
        // Fixture control: the expected order above is only interesting while the SORT is still
        // FILENAME. A stray preference left by another method — the DataStore is shared across
        // this JVM — would make this test assert a different question under the same name.
        assertEquals(
            "the fixture stopped exercising the direction alone; the sort is no longer FILENAME",
            TrackSort.FILENAME,
            state.sort,
        )
    }

    // -------------------------------------------------------------------------------- the shuffle

    /**
     * Shuffle hands over the same SET the scope produces, permuted — nothing added, nothing lost.
     *
     * **Two assertions, because the sorted one alone cannot see a shuffle that never happened.**
     * `queuedNames().sorted()` is satisfied by `play(folderTracks(…), 0)`, so on its own the word
     * "permuted" in this test's name would be a claim nothing checks — the defect noted in review,
     * 2026-08-13. The second assertion is what earns it.
     *
     * The exact permutation is still NOT asserted: that would pin an implementation detail of
     * `List.shuffled` / `Collections.shuffle` rather than any property of this app. What is
     * asserted is the weakest thing that excludes "no shuffle at all" — that the queue is not the
     * deep order. Deterministic, because [Random] is seeded and the fixture is fixed; verified by
     * execution rather than by probability, so a seed that happened to produce the identity
     * permutation would have been caught here rather than shipping as a flake.
     */
    @Test fun `shuffle queues the whole scope, permuted`() = runTest {
        val vm = albumTree()
        val state = vm.settled()
        val deepOrder = listOf(
            "01 a.mp3", "05 c.mp3",
            "01 d1a.mp3", "02 d1b.mp3", "01 bonus.mp3",
            "01 d2a.mp3",
            "01 d10a.mp3",
        )

        vm.shuffleFolder(state, node(state, ALBUM), FolderScope.WITH_SUBFOLDERS, Random(7))

        assertEquals(
            "the shuffled queue is not the deep scope's tracks — something was added or lost",
            deepOrder.sorted(),
            queuedNames().sorted(),
        )
        assertNotEquals(
            "the queue came out in deep order, so nothing was shuffled — `shuffleFolder` handed " +
                "the list straight to the player",
            deepOrder,
            queuedNames(),
        )
    }
}
