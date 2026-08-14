// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.scan.LibraryScanWorker
import com.kaislate.veldtplayer.data.library.SourceRegistry
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.data.library.UNFILED_KEY
import com.kaislate.veldtplayer.data.library.VolumeNames
import com.kaislate.veldtplayer.data.library.db.IndexEntry
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.playback.PlaybackConnection
import com.kaislate.veldtplayer.ui.nav.Destinations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowStorageManager
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The folder tab as the SCREEN reaches it: the real tree derivation, the real elision, the real
 * preference store and the real [VolumeNames] lookup.
 *
 * **The two-volume case is why this file exists.** Elision folds each volume down to its first
 * interesting directory INDEPENDENTLY, and on a phone with an SD card that is `Music` on both — so
 * a top-level row named from `displayRoot.name` gives the user two identical rows and no way to
 * tell internal storage from the card. Nothing anywhere else asserts that join, and no device on
 * this fleet has a card, so this fixture is the only evidence the behaviour will ever have.
 *
 * Robolectric because three of the four inputs touch Android: [VolumeNames] reads a real
 * `StorageManager`, [SettingsRepository] writes a real DataStore file, and
 * [MusicRepository.scanning] reads WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other suites are. 34 also takes
// VolumeNames' API-30+ branch, which is what a device on this floor would take.
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class FolderViewModelTest {

    /**
     * Emits whatever song-list flow it is handed — a fixed script, or one this test drives.
     *
     * **Never a `MutableStateFlow`**, for the reason `MusicRepositoryFolderTreeTest` records: a
     * `StateFlow` conflates equal values on its own, so a fake built on one supplies conflation the
     * production code may not have and the test ends up asserting the fake.
     *
     * The live-refresh tests below drive it with a `MutableSharedFlow(replay = 1)`, which is NOT
     * that trap: a replay buffer re-delivers the last value to a late subscriber, but it drops
     * nothing and it suppresses nothing — every emission reaches the collector, equal to the
     * previous one or not. That is the property those tests need, because the conflation under test
     * is `MusicRepository.folderTree`'s own `distinctUntilChanged`.
     */
    private class FakeSongDao(private val script: Flow<List<SongEntity>>) : SongDao {
        override fun observeAllSongs(): Flow<List<SongEntity>> = script
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
    private lateinit var shadowStorage: ShadowStorageManager
    private var vm: FolderViewModel? = null
    private var nextId = 1L

    private companion object {
        const val BECK = "external_primary:Music/Beck"
    }

    /**
     * A worker that starts and never finishes, as `ScanSingleFlightTest` uses.
     *
     * Two things make it necessary rather than tidy. The real [LibraryScanWorker] is a
     * `@HiltWorker` with an `@AssistedInject` constructor, so the default factory cannot build it
     * and the work would go straight to FAILED — which `MusicRepository.scanning()` reads as *no
     * scan*, the very value the test below has to distinguish itself from. And [SynchronousExecutor]
     * runs the worker inline on enqueue, so a worker that completed would flip the flag back down
     * before anything could observe it. A future that never resolves holds the work RUNNING.
     */
    private class StuckWorker(ctx: Context, params: WorkerParameters) :
        ListenableWorker(ctx, params) {
        override fun startWork(): ListenableFuture<Result> = NeverFuture()
    }

    private class NeverFuture : ListenableFuture<ListenableWorker.Result> {
        override fun addListener(listener: Runnable, executor: Executor) = Unit
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = false
        override fun get(): ListenableWorker.Result = throw UnsupportedOperationException()
        override fun get(timeout: Long, unit: TimeUnit): ListenableWorker.Result =
            throw UnsupportedOperationException()
    }

    private class StuckFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker = StuckWorker(appContext, workerParameters)
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        // MusicRepository.scanning() reads WorkManager. With nothing enqueued it reports false,
        // which is what a settled library looks like; the substitute factory is what lets one test
        // here put a scan IN FLIGHT and keep it there. See StuckWorker.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setWorkerFactory(StuckFactory())
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        settings = SettingsRepository(context)
        // One DataStore is shared by every test method in this JVM, so a sort written by one test
        // would otherwise decide another's track order. See SettingsRepositoryTest.
        runBlocking { settings.clearForTest() }
        shadowStorage = Shadow.extract(context.getSystemService(StorageManager::class.java))
    }

    @After fun tearDown() {
        // Before anything else: `state` is stateIn(viewModelScope, WhileSubscribed) and is live
        // from construction, holding flows over the DataStore this test's context owns.
        vm?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------------- fixtures

    private fun row(relativeKey: String, title: String = "t") = SongEntity(
        id = nextId++, sourceId = "test", externalId = "e$nextId", uri = "content://x",
        filePath = null, relativeKey = relativeKey,
        title = title, artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    /** A song with no location at all — neither a relativeKey nor a filePath. */
    private fun unlocatedRow() = SongEntity(
        id = nextId++, sourceId = "test", externalId = "e$nextId", uri = "content://x",
        filePath = null, relativeKey = null,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    private fun viewModel(vararg rows: SongEntity): FolderViewModel =
        viewModel(flowOf(rows.toList()))

    private fun viewModel(library: Flow<List<SongEntity>>): FolderViewModel {
        val repo = MusicRepository(
            FakeSongDao(library),
            SourceRegistry(emptySet()),
            context,
        )
        return FolderViewModel(
            repo = repo,
            settings = settings,
            volumeNames = VolumeNames(context),
            connection = PlaybackConnection(context, repo),
        ).also { vm = it }
    }

    /**
     * `setIsPrimary(false)` is load-bearing — `StorageVolumeBuilder` defaults it to true and a
     * primary volume reports `external_primary` as its media-store name whatever uuid it carries,
     * so without it the fixture describes internal storage twice. See `VolumeNamesTest`.
     */
    private fun addCard(fsUuid: String, description: String) {
        shadowStorage.addStorageVolume(
            StorageVolumeBuilder(
                "stub-id", File("/storage/$fsUuid"), description,
                Process.myUserHandle(), Environment.MEDIA_MOUNTED,
            ).setFsUuid(fsUuid).setIsPrimary(false).setIsRemovable(true).build()
        )
    }

    /** The first state carrying a derived tree. The seed has none, and `scanning` starts true. */
    private suspend fun FolderViewModel.settled(): FolderUiState =
        state.first { it.roots.isNotEmpty() }

    // ------------------------------------------------------------------- the two-volume join

    /**
     * **The one that matters.** Two volumes, each holding `Music/` with two artists under it, so
     * elision stops at `Music` on BOTH and the two display roots have the same `name`.
     *
     * The node names are asserted alongside the labels on purpose: they are what makes this fixture
     * a real trap rather than a fixture that would pass under either rule. If they ever stop being
     * equal the test still passes but stops testing anything, and the failure message says so.
     *
     * The CARD's rows come first in the fixture, which is what also makes the row ORDER load-bearing
     * here. `FolderTree.build` introduces volumes in song order and `FolderSort.folders` orders by
     * `name` — equal for both, so a stable sort leaves the card on top. Only ordering by the LABEL
     * puts internal storage first, and only a fixture in this order can tell the two apart.
     */
    @Test fun `two volumes are told apart by their volume label, not by their folder name`() = runTest {
        addCard(fsUuid = "1234-5678", description = "SanDisk Ultra")
        val vm = viewModel(
            row("1234-5678:Music/Beck/c.mp3"),
            row("1234-5678:Music/Radiohead/d.mp3"),
            row("external_primary:Music/Beck/a.mp3"),
            row("external_primary:Music/Radiohead/b.mp3"),
        )

        val listing = vm.listing(vm.settled(), null)

        assertEquals(
            "the top level is not labelled AND ordered by volume — it fell back to the folder " +
                "name, which is 'Music' on both",
            listOf("Internal storage", "SanDisk Ultra"),
            listing.folders.map { it.label },
        )
        assertEquals(
            "the fixture no longer collides: both display roots must be named 'Music' for the " +
                "label rule to be under test at all",
            listOf("Music", "Music"),
            listing.folders.map { it.node.name },
        )
    }

    /** And each volume row opens on that volume's own subtree, not on the other's. */
    @Test fun `each volume row leads to its own folders`() = runTest {
        addCard(fsUuid = "1234-5678", description = "SanDisk Ultra")
        val vm = viewModel(
            row("external_primary:Music/Beck/a.mp3"),
            row("external_primary:Music/Radiohead/b.mp3"),
            row("1234-5678:Music/Portishead/c.mp3"),
            row("1234-5678:Music/Tricky/d.mp3"),
        )
        val state = vm.settled()

        assertEquals(
            "a volume row opened the wrong volume's subtree",
            listOf(
                "Internal storage" to listOf("Beck", "Radiohead"),
                "SanDisk Ultra" to listOf("Portishead", "Tricky"),
            ),
            vm.listing(state, null).folders.map { volume ->
                volume.label to vm.listing(state, volume.node.key).folders.map { it.label }
            },
        )
    }

    // ----------------------------------------------------------------- one volume, and elision

    /**
     * One volume opens on the ARTIST folders, not on a single row reading `Music` — the whole point
     * of root elision. These rows are NOT volume rows, so they carry their own names.
     */
    @Test fun `one volume opens on the elided display root's own folders`() = runTest {
        val vm = viewModel(
            row("external_primary:Music/Beck/a.mp3"),
            row("external_primary:Music/Radiohead/b.mp3"),
        )

        assertEquals(
            "the tab root did not open on the elided display root",
            listOf("Beck", "Radiohead"),
            vm.listing(vm.settled(), null).folders.map { it.label },
        )
    }

    /**
     * The breadcrumb renders what elision hid, and it says which crumbs are destinations.
     *
     * `Internal storage` is elided and therefore INERT — there is no destination for a level the
     * design removed, and the pop-or-navigate fallback would push a duplicate rather than pop.
     * `Music` is the display root, which with one volume IS the tab root, so it pops to `folders`
     * rather than to a `folder/…` entry that was never on the stack.
     */
    @Test fun `the breadcrumb renders the elided ancestors and routes only what is reachable`() = runTest {
        val vm = viewModel(
            row("external_primary:Music/Beck/Sea Change/a.mp3"),
            row("external_primary:Music/Radiohead/x.mp3"),
        )

        val crumbs = vm.listing(vm.settled(), "external_primary:Music/Beck/Sea Change").crumbs

        assertEquals(
            "the breadcrumb lost a level elision hid, or mislabelled the volume",
            listOf("Internal storage", "Music", "Beck", "Sea Change"),
            crumbs.map { it.label },
        )
        assertEquals(
            "an elided ancestor became tappable, or the display root does not pop to the tab root",
            listOf(null, Destinations.FOLDERS, Destinations.folder("external_primary:Music/Beck"), null),
            crumbs.map { it.route },
        )
    }

    /**
     * The same breadcrumb, with TWO volumes — the fixture the single-volume test above cannot be.
     *
     * Two things only this shape can pin, and the fixture is built so each has a distinguishable
     * wrong answer:
     *
     * - **The display root's crumb must point at that volume's own destination**, not at the tab
     *   root. With one volume the two are the same thing; with two they are not, and popping to
     *   `folders` from inside the card would jump past the `folder/1234-5678:Music` entry that is
     *   actually on the stack.
     * - **The elided depth must be read from the crumb's OWN volume.** The two volumes here have
     *   deliberately DIFFERENT display roots — internal storage branches at its root (`Music` and
     *   `Download`) so nothing is elided and its depth is 0, while the card elides its volume node
     *   and its depth is 1 — so a lookup keyed on anything but the node's volume marks the wrong
     *   crumbs inert.
     *
     * **Both volumes are asked, and that is what makes the lookup pinned in both directions.** With
     * only the card's crumbs asserted, `roots.lastOrNull()` and a hardcoded `displayDepth = 1` both
     * pass — this fixture's card *is* the last root and its depth *is* 1. The internal-storage
     * assertion at the bottom is the one those two fail, and Task 6 depends on it: `displayDepth`
     * decides which crumbs are inert, which is exactly which branch of the pop-or-navigate fallback
     * a tap can reach.
     */
    @Test fun `each volume's breadcrumb is measured against its own elided depth`() = runTest {
        addCard(fsUuid = "1234-5678", description = "SanDisk Ultra")
        val vm = viewModel(
            // Internal storage first, so `roots.first()` is the volume with the OTHER display depth.
            row("external_primary:Music/a.mp3"),
            row("external_primary:Download/b.mp3"),
            row("1234-5678:Music/Beck/Sea Change/c.mp3"),
            row("1234-5678:Music/Radiohead/d.mp3"),
        )
        val state = vm.settled()

        val card = vm.listing(state, "1234-5678:Music/Beck/Sea Change").crumbs
        assertEquals(
            "the card's breadcrumb is mislabelled — the volume label, then its own path",
            listOf("SanDisk Ultra", "Music", "Beck", "Sea Change"),
            card.map { it.label },
        )
        assertEquals(
            "the display root's crumb does not pop to its own volume, or the elided depth was " +
                "read from a volume that elides LESS than this one",
            listOf(
                null,
                Destinations.folder("1234-5678:Music"),
                Destinations.folder("1234-5678:Music/Beck"),
                null,
            ),
            card.map { it.route },
        )

        // Internal storage elides NOTHING here, so its volume crumb is a real destination rather
        // than an inert one. This is the assertion `roots.lastOrNull()` and a hardcoded depth fail.
        val internal = vm.listing(state, "external_primary:Music").crumbs
        assertEquals(
            "internal storage's breadcrumb is wrong, or the elided depth was read from a volume " +
                "that elides MORE than this one — its volume crumb went inert",
            listOf(
                "Internal storage" to Destinations.folder("external_primary"),
                "Music" to null,
            ),
            internal.map { it.label to it.route },
        )
    }

    // ---------------------------------------------------------------------- what a folder lists

    /** Child directories by natural name, and the folder's OWN tracks — held apart, not merged. */
    @Test fun `a folder lists its child directories and its own tracks, each in its own order`() = runTest {
        val vm = viewModel(
            row("external_primary:Music/Beck/a.mp3"),
            row("external_primary:Music/Aaa/z.mp3"),
            row("external_primary:Music/10 zz.mp3"),
            row("external_primary:Music/2 aa.mp3"),
        )

        val listing = vm.listing(vm.settled(), null)

        assertEquals(
            "the child directories are not in natural name order",
            listOf("Aaa", "Beck"),
            listing.folders.map { it.label },
        )
        assertEquals(
            "the folder's own tracks are not in natural FILE NAME order — '10' sorted before '2'",
            listOf("2 aa.mp3", "10 zz.mp3"),
            listing.tracks.map { it.relativeKey?.substringAfterLast('/') },
        )
    }

    /**
     * The stored preference reaches the track order — the assertion that fails if [FolderUiState]'s
     * sort is carried but never applied.
     *
     * Both orders are asserted, because either one alone is satisfied by a listing that always uses
     * the other: the filenames and the titles are deliberately in OPPOSITE orders.
     */
    @Test fun `the stored sort preference decides the track order`() = runTest {
        val vm = viewModel(
            row("external_primary:Music/a.mp3", title = "Zulu"),
            row("external_primary:Music/b.mp3", title = "Alpha"),
        )

        val byFilename = vm.listing(vm.state.first { it.roots.isNotEmpty() }, null)
        assertEquals(
            "the default order is not by file name",
            listOf("Zulu", "Alpha"),
            byFilename.tracks.map { it.title },
        )

        vm.setSort(TrackSort.TITLE)

        val byTitle = vm.listing(vm.state.first { it.sort == TrackSort.TITLE }, null)
        assertEquals(
            "the stored sort never reached FolderSort.tracks",
            listOf("Alpha", "Zulu"),
            byTitle.tracks.map { it.title },
        )
    }

    // -------------------------------------------------------------------------- the odd states

    /** A folder that is not in the tree yields no node, so the screen can say so rather than
     *  drawing an empty list that looks like an empty folder. */
    @Test fun `a key that names no folder resolves to nothing`() = runTest {
        val vm = viewModel(row("external_primary:Music/Beck/a.mp3"))
        val state = vm.settled()

        assertNull(
            "a key naming no folder resolved to something",
            vm.listing(state, "external_primary:Music/Gone").node,
        )
        // The complement, so the assertion above is not satisfied by a listing that resolves
        // NOTHING: the sibling key that does exist must still resolve.
        assertEquals(
            "a key that does exist stopped resolving",
            "external_primary:Music/Beck",
            vm.listing(state, "external_primary:Music/Beck").node?.key,
        )
    }

    /**
     * The empty string resolves to nothing, which is what makes `VeldtNavHost`'s `?: ""` safe.
     *
     * `null` is this screen's value for the TAB ROOT, so an absent route argument must not arrive
     * as null. `""` is the substitute, and it only works because no node can be keyed on it — every
     * key begins with a volume name, and a volume name is never empty. Asserted rather than
     * assumed: nothing in this source set can drive the nav host, so this is where that line's
     * premise is checkable at all.
     */
    @Test fun `the empty key is not the tab root — it names no folder`() = runTest {
        val vm = viewModel(row("external_primary:Music/Beck/a.mp3"))

        assertNull(
            "the empty string resolved to a folder, so an absent route argument would open it",
            vm.listing(vm.settled(), "").node,
        )
    }

    /**
     * The seed reports a scan IN FLIGHT, before the first emission arrives.
     *
     * Seeded false, an empty library renders "No folders yet" — with a Scan button WorkManager's
     * KEEP would no-op — for the frames between audio access being granted and the first tree
     * landing. That is the bug `BrowseViewModel.scanning`'s KDoc records, and nothing else here
     * would notice the seed being quietly changed back. Read from `value` with no collector, which
     * is exactly the window the screen sees on its first frame.
     *
     * **And it has to come back DOWN**, which is the half a seed assertion alone cannot see: pinned
     * true and nothing else, `scanning = true` hardcoded in the combine passes.
     *
     * **What that costs, corrected 2026-08-13 — the earlier version of this KDoc named only half of
     * it.** `state.scanning` reaches TWO branches in `FolderScreen`. The first is guarded by
     * `roots.isEmpty()`, so a populated library still lists its folders and it is an EMPTY library
     * that sits under the spinner with no way to ask for a scan. That much was right. The second
     * branch is `listing.node == null`, which is **not** guarded by `roots.isEmpty()`: a folder key
     * that resolves to nothing — a folder deleted under an open screen, a back stack restored across
     * a rescan, or `VeldtNavHost`'s `?: ""` fallback — renders `ScanningState` FOREVER instead of
     * "Folder unavailable", on a fully populated library, with no Go back button. That is a dead
     * end on a working library, and it is the live-refresh window Task 6's behaviour 2 reasons
     * about. Both halves are pinned below: see `a folder that vanishes mid-scan is not reported
     * missing yet` and `a dead key over a settled library reports the folder unavailable`.
     *
     * This test cannot pin the other direction. Nothing here enqueues work, so the repository
     * honestly reports `false` and `scanning = false` hardcoded produces the same value — see
     * `a scan in flight is reported over a library that already has folders`, which is the half
     * that catches it.
     */
    @Test fun `the tab assumes a scan is coming until the library says otherwise`() = runTest {
        val vm = viewModel(row("external_primary:Music/Beck/a.mp3"))

        assertEquals(
            "the seeded state reports no scan, so the first frames of a fresh install say " +
                "'No folders yet' over a library that is still being read",
            true,
            vm.state.value.scanning,
        )
        assertEquals(
            "the flag never came down — an empty library would sit under the scanning spinner " +
                "with no way to ask for a scan",
            false,
            vm.settled().scanning,
        )
    }

    /**
     * A scan IN FLIGHT is reported — the half the test above is structurally unable to see.
     *
     * That test's fixture never enqueues anything, so `MusicRepository.scanning()` truthfully
     * answers `false` for its whole run and `scanning = false` hardcoded in the combine yields the
     * same value. Executed: that mutation survived the entire 653-test suite. `false` is a value
     * the wrong implementation also produces, which is exactly what an assertion may not rest on.
     *
     * So this one enqueues real work through the real verb — `vm.scan()` reaches
     * `MusicRepository.requestScan()` and `LibraryScanWorker.enqueue` — and asserts a value only a
     * live read can produce. The work is enqueued BEFORE the first collector subscribes, so the
     * claim does not depend on WorkManager's flow re-emitting under a test scheduler: `state` is
     * `WhileSubscribed`, so the first emission it ever makes already sees the running scan.
     *
     * The library is deliberately POPULATED. This is the case the surviving mutant broke and the
     * one the test above cannot reach: a rescan over folders the user can already see.
     */
    @Test fun `a scan in flight is reported over a library that already has folders`() = runTest {
        val vm = viewModel(row("external_primary:Music/Beck/a.mp3"))

        vm.scan()

        assertEquals(
            "the scanning flag is not read from the repository at all — a rescan over a " +
                "populated library would never render ScanningState",
            true,
            vm.settled().scanning,
        )
    }

    /**
     * Every song unfiled: the tab is unusable and owes the user the reason. Reported as state
     * rather than as an empty list, which would blame the music for a media-index problem.
     */
    @Test fun `a library whose songs have no locations reports itself unavailable`() = runTest {
        val unfiled = viewModel(unlocatedRow(), unlocatedRow()).settled()
        assertEquals(
            "the all-unfiled library is not reported as having no locations",
            listOf<Any?>(true, listOf(UNFILED_KEY)),
            listOf<Any?>(unfiled.locationsUnavailable, unfiled.roots.map { it.displayRoot.key }),
        )

        // The complement: one located song is enough for the tab to work, so this flag must not
        // simply be "the bucket exists".
        vm?.viewModelScope?.cancel()
        val mixed = viewModel(unlocatedRow(), row("external_primary:Music/a.mp3")).settled()
        assertEquals(
            "a library with one located song was reported unusable",
            false,
            mixed.locationsUnavailable,
        )
    }

    // ------------------------------------------------------------- live refresh (Task 6, step 4)

    /**
     * A library this test drives emission by emission.
     *
     * `replay = 1` so a value emitted before `state`'s `WhileSubscribed` upstream attaches is not
     * dropped — see [FakeSongDao] for why that is not the conflating-double trap. Each emission is
     * awaited with a `state.first { … }` on a property of the emission itself rather than with a
     * scheduler advance, so nothing here depends on how many dispatches a DataStore read takes.
     */
    private fun liveLibrary() = MutableSharedFlow<List<SongEntity>>(replay = 1)

    /**
     * **Behaviour 1: a folder gains tracks while its screen is open.**
     *
     * The arriving track joins the rows already there, in sort position — it does not replace them,
     * and the folder does not empty and refill. Asserted as both listings in one pair, because the
     * "before" half is what makes the "after" half mean anything: a derivation that always returned
     * the whole library would satisfy the second list alone.
     *
     * The screen's half of this behaviour — new rows animating in rather than jumping — is
     * `Modifier.animateItem()` in `FolderScreen`, which no JVM test in this project can reach. It is
     * device-matrix row 14.
     */
    @Test fun `a folder that gains a track keeps the rows already on screen`() = runTest {
        val library = liveLibrary()
        val vm = viewModel(library)
        val first = row("external_primary:Music/Beck/01 a.mp3")
        val arriving = row("external_primary:Music/Beck/02 b.mp3")

        library.emit(listOf(first))
        val before = vm.settled()
        library.emit(listOf(first, arriving))
        val after = vm.state.first { s -> vm.listing(s, BECK).tracks.any { it.id == arriving.id } }

        assertEquals(
            "the arriving track did not join the rows already on screen — the folder was " +
                "rebuilt from the new song alone, or the old rows were dropped",
            listOf(listOf("01 a.mp3"), listOf("01 a.mp3", "02 b.mp3")),
            listOf(
                vm.listing(before, BECK).tracks.map { it.relativeKey?.substringAfterLast('/') },
                vm.listing(after, BECK).tracks.map { it.relativeKey?.substringAfterLast('/') },
            ),
        )
    }

    /**
     * **Behaviour 2, first half: the folder the user is IN vanishes MID-SCAN.**
     *
     * Reported as `node == null` with `scanning` still TRUE, which is the pair `FolderScreen` reads
     * to render `ScanningState` rather than "Folder unavailable". **It does not pop**: a transient
     * empty emission during a scan would otherwise yank the screen out from under a thumb that is
     * already moving, and a scan emits partial trees by design (see `MusicRepository.folderTree`).
     *
     * The library is deliberately POPULATED and the SIBLING folders are asserted alongside, because
     * `roots.isEmpty()` guards the other `scanning` branch: without them this would pass over an
     * empty library, which is a different screen entirely. Two siblings and not one, so that
     * removing `Gone/` leaves `Music/` with more than one child — with a single survivor
     * `FolderTree.elideRoots` folds the chain down to that survivor and the tab root would have no
     * rows at all, which is a different behaviour wearing this test's name.
     */
    @Test fun `a folder that vanishes mid-scan is not reported missing yet`() = runTest {
        val library = liveLibrary()
        val vm = viewModel(library)
        val beck = row("external_primary:Music/Beck/a.mp3")
        val radiohead = row("external_primary:Music/Radiohead/c.mp3")
        val gone = row("external_primary:Music/Gone/b.mp3")

        library.emit(listOf(beck, radiohead, gone))
        val before = vm.settled()
        // Fixture control: the folder about to vanish has to be there first, or the assertion
        // below is about a key that never resolved.
        assertEquals(
            "the fixture never contained the folder that is supposed to vanish",
            "external_primary:Music/Gone",
            vm.listing(before, "external_primary:Music/Gone").node?.key,
        )

        // A real scan, in flight and held there by StuckWorker — the same verb the screen calls.
        vm.scan()
        library.emit(listOf(beck, radiohead))
        val during = vm.state.first { vm.listing(it, "external_primary:Music/Gone").node == null }

        assertEquals(
            "a folder that vanished mid-scan is reported as gone rather than as still arriving, " +
                "so the screen would offer 'Folder unavailable' during a scan that may yet " +
                "restore it — or the rest of the library disappeared with it",
            listOf<Any?>(true, listOf("Beck", "Radiohead")),
            listOf<Any?>(during.scanning, vm.listing(during, null).folders.map { it.label }),
        )
    }

    /**
     * **Behaviour 2, second half: the scan settles and the folder is still gone.**
     *
     * `node == null` with `scanning` FALSE — the pair that renders "Folder unavailable" with a Go
     * back button. This is the assertion the corrected KDoc above names: `scanning = true`
     * hardcoded leaves this branch under a spinner forever on a library that is working fine.
     *
     * Nothing here enqueues work, so the repository truthfully reports `false`; the value is
     * therefore one a hardcoded `false` also produces, and the OTHER direction is pinned by
     * `a scan in flight is reported over a library that already has folders`. Neither test covers
     * both, which is why both exist.
     */
    @Test fun `a dead key over a settled library reports the folder unavailable`() = runTest {
        val vm = viewModel(
            row("external_primary:Music/Beck/a.mp3"),
            row("external_primary:Music/Radiohead/b.mp3"),
        )
        val settled = vm.settled()

        assertEquals(
            "an unresolvable key over a settled, populated library does not reach the " +
                "'Folder unavailable' surface — node and scanning must be (null, false)",
            listOf<Any?>(null, false, listOf("Beck", "Radiohead")),
            listOf<Any?>(
                vm.listing(settled, "external_primary:Music/Gone").node,
                settled.scanning,
                vm.listing(settled, null).folders.map { it.label },
            ),
        )
    }

    /**
     * **Behaviour 3: a whole volume disappears.**
     *
     * The tab root stops being a volume chooser and becomes the surviving volume's own folders —
     * accepted, and the reason it is acceptable is the second assertion: the breadcrumb still names
     * the volume, so the level that vanished from the ROWS did not vanish from the truth about
     * where the user is.
     *
     * The same song entities are re-emitted rather than rebuilt, so the only difference between the
     * two emissions is the volume — an entity rebuilt with a fresh id would also change every
     * `song.id` and make this test about something else.
     */
    @Test fun `a volume that disappears leaves the surviving volume's own folders`() = runTest {
        addCard(fsUuid = "1234-5678", description = "SanDisk Ultra")
        val library = liveLibrary()
        val vm = viewModel(library)
        val beck = row("external_primary:Music/Beck/a.mp3")
        val radiohead = row("external_primary:Music/Radiohead/b.mp3")
        val card = row("1234-5678:Music/Portishead/c.mp3")

        library.emit(listOf(beck, radiohead, card))
        val two = vm.state.first { it.roots.size == 2 }
        library.emit(listOf(beck, radiohead))
        val one = vm.state.first { it.roots.size == 1 }

        assertEquals(
            "the tab root did not stop being a volume chooser when the card went away",
            listOf(listOf("Internal storage", "SanDisk Ultra"), listOf("Beck", "Radiohead")),
            listOf(
                vm.listing(two, null).folders.map { it.label },
                vm.listing(one, null).folders.map { it.label },
            ),
        )
        assertEquals(
            "the breadcrumb stopped naming the volume once the chooser row disappeared, so the " +
                "user has no way left to tell which volume they are looking at",
            listOf(listOf("Internal storage", "Music", "Beck"), listOf("Internal storage", "Music", "Beck")),
            listOf(
                vm.listing(two, BECK).crumbs.map { it.label },
                vm.listing(one, BECK).crumbs.map { it.label },
            ),
        )
    }

    /**
     * **Behaviour 4: identity across a re-derivation.**
     *
     * `FolderScreen` keys its rows on `FolderNode.key`, and the whole tree is rebuilt object by
     * object on every emission — so if a key were derived from anything per-emission, every row
     * would be a NEW item on every scan batch and the list would scroll back to the top mid-scan.
     *
     * `assertNotSame` is the half that makes the rest mean something: it proves the tree really was
     * re-derived, so the equal keys are a property of the KEY rather than of nothing having
     * happened. Without it a `distinctUntilChanged` that suppressed the second emission entirely
     * would satisfy every other assertion here.
     *
     * `song.id` is deliberately NOT asserted: it is the Room row id copied straight through, so it
     * is stable by construction and an assertion on it would pin the fixture rather than the code.
     * The scroll position surviving a real rescan is device-matrix row 12.
     */
    @Test fun `a rescan rebuilds the tree without changing the keys the list is drawn on`() = runTest {
        val library = liveLibrary()
        val vm = viewModel(library)
        val beck = row("external_primary:Music/Beck/a.mp3")
        val radiohead = row("external_primary:Music/Radiohead/b.mp3")
        val arriving = row("external_primary:Music/Radiohead/c.mp3")

        library.emit(listOf(beck, radiohead))
        val before = vm.settled()
        val beforeRows = vm.listing(before, null).folders
        library.emit(listOf(beck, radiohead, arriving))
        val after = vm.state.first { s ->
            vm.listing(s, "external_primary:Music/Radiohead").tracks.any { it.id == arriving.id }
        }
        val afterRows = vm.listing(after, null).folders

        assertEquals(
            "a folder key changed across a rescan — every row would be a new LazyColumn item " +
                "and the list would jump back to the top mid-scan",
            listOf(BECK, "external_primary:Music/Radiohead"),
            afterRows.map { it.node.key },
        )
        assertEquals(
            "the keys before and after a rescan disagree",
            beforeRows.map { it.node.key },
            afterRows.map { it.node.key },
        )
        assertNotSame(
            "the tree was NOT re-derived between the two emissions, so the equal keys above are " +
                "asserting nothing at all",
            beforeRows.first().node,
            afterRows.first().node,
        )
    }
}
