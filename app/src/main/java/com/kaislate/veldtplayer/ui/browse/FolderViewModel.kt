// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.FolderNode
import com.kaislate.veldtplayer.data.library.FolderRoot
import com.kaislate.veldtplayer.data.library.FolderSort
import com.kaislate.veldtplayer.data.library.FolderTree
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.data.library.UNFILED_KEY
import com.kaislate.veldtplayer.data.library.VolumeNames
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.playback.PlaybackConnection
import com.kaislate.veldtplayer.ui.nav.Destinations
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * The key of the SYNTHETIC node that lists the volumes when there is more than one.
 *
 * NUL-prefixed for the same reason as [UNFILED_KEY]: no real volume, and therefore no real folder
 * key, can collide with it. It is never a route — the tab root is [Destinations.FOLDERS] — so this
 * value never reaches a back stack.
 *
 * Spelled `Char.MIN_VALUE`, which IS the NUL character, rather than as a string escape. A raw NUL
 * inside a Kotlin string literal is invisible in a diff and survives editors and tooling poorly —
 * one silently rewrote it to a space here, which would have made the sentinel an ordinary string.
 */
internal val DEVICE_KEY: String = Char.MIN_VALUE + "device"

/** What the tab root is called when it lists volumes rather than one volume's folders. */
private const val DEVICE_LABEL = "Folders"

/**
 * The whole folder tab, in one value.
 *
 * [roots] is already elided ([FolderTree.elideRoots]) — the UI never sees the raw volume roots
 * except through [FolderRoot.elided], which is what the breadcrumb renders.
 */
data class FolderUiState(
    val roots: List<FolderRoot> = emptyList(),
    val sort: TrackSort = TrackSort.FILENAME,
    val descending: Boolean = false,
    val scanning: Boolean = false,
) {
    /**
     * The library has songs, and not one of them has a derivable location.
     *
     * [FolderTree.build] buckets such songs under [UNFILED_KEY], so this is exactly "the only root
     * is the Unfiled bucket". The screen owes this case a designed message rather than a blank
     * list: the tab is unusable, and the reason is the media index, not the user's music.
     *
     * A bucket that coexists with a real volume is NOT this case — it is then an ordinary
     * top-level row labelled "Unfiled", which is where those songs stay reachable.
     */
    val locationsUnavailable: Boolean
        get() = roots.size == 1 && roots.single().displayRoot.key == UNFILED_KEY
}

/** One directory row: the node, and the label the row actually draws. See [FolderListing]. */
data class FolderRowItem(val node: FolderNode, val label: String)

/** One breadcrumb segment. A null [route] is inert — the current folder, or an elided ancestor. */
data class FolderCrumb(val label: String, val route: String?)

/**
 * Everything one folder listing draws, derived ONCE per (state, key) pair.
 *
 * [node] is null when the key names no folder in the current tree — a folder that vanished under an
 * open screen, or a back stack restored across a rescan. The screen renders that as its own
 * surface; it does not pop (see Task 6).
 */
data class FolderListing(
    val node: FolderNode?,
    val crumbs: List<FolderCrumb>,
    val folders: List<FolderRowItem>,
    val tracks: List<Song>,
)

/** The listing a key that names nothing produces. */
private val NO_LISTING = FolderListing(null, emptyList(), emptyList(), emptyList())

/**
 * The Folders tab and every folder under it.
 *
 * **Constraint 14, the collector half.** [MusicRepository.folderTree] derives the tree once per
 * distinct emission; this collects it into a [StateFlow] with
 * `stateIn(viewModelScope, WhileSubscribed(5_000), …)`, so a burst of scan batches collapses to the
 * newest tree and a recomposition re-reads a value rather than re-deriving one. [FolderTree.find]
 * is called from [listing] and from nowhere else — never from a composable body and never from a
 * row. What that buys, stated exactly: the walk runs once per (state, key) pair, because the screen
 * holds the result in a `remember` keyed on both. It does **not** run once per process, and a new
 * tree emission does re-walk it — which is the point, since the previous emission's nodes are gone.
 *
 * One instance serves every folder destination, hoisted in `VeldtNavHost` for the reason recorded
 * there: `hiltViewModel()` inside a `composable { }` is scoped to that back-stack entry, so four
 * folders deep would be four instances and four concurrent collectors of `folderTree()` — and that
 * flow re-derives per collector rather than sharing.
 */
@HiltViewModel
class FolderViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val settings: SettingsRepository,
    private val volumeNames: VolumeNames,
    private val connection: PlaybackConnection,
) : ViewModel() {

    /**
     * Seeded `scanning = true`, not with a bare [FolderUiState].
     *
     * Same reasoning as [BrowseViewModel.scanning], and the same bug if it is dropped: the nav host
     * enqueues a scan the instant audio access is granted but WorkManager reports back
     * asynchronously, so a false seed renders "No folders yet" — and a Scan button `KEEP` would
     * no-op — for the frames before the first emission lands.
     */
    val state: StateFlow<FolderUiState> = combine(
        repo.folderTree(),
        settings.folderSort,
        settings.folderSortDescending,
        repo.scanning(),
    ) { tree, sort, descending, scanning ->
        FolderUiState(
            roots = FolderTree.elideRoots(tree),
            sort = sort,
            descending = descending,
            scanning = scanning,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FolderUiState(scanning = true),
    )

    /**
     * The node a route key names, or the tab root when [key] is null.
     *
     * Reads the CURRENT state value; it does not re-derive the tree (global constraint 14).
     */
    fun nodeFor(key: String?): FolderNode? = listing(state.value, key).node

    /**
     * What one listing draws: its breadcrumb, its directories, and its tracks.
     *
     * Takes the state rather than reading [state] itself so the caller can `remember` the result on
     * it — the screen does, which is what keeps the [FolderTree.find] walk off the recomposition
     * path.
     *
     * **Directories first, then tracks, never intermixed.** That is the file-manager contract, and
     * it differs from every other Veldt surface because every other surface lists one kind of thing.
     */
    fun listing(state: FolderUiState, key: String?): FolderListing {
        val roots = state.roots
        val node = (if (key == null) homeNode(roots) else find(roots, key)) ?: return NO_LISTING

        // Only the synthetic node's children are volumes. One volume's tab root is the elided
        // display root itself, whose children are ordinary directories.
        val volumeRows = node.key == DEVICE_KEY
        val folders = FolderSort.folders(node.children)
            .map { child -> FolderRowItem(child, rowLabel(child, volumeRows)) }
        return FolderListing(
            node = node,
            crumbs = if (volumeRows) listOf(FolderCrumb(DEVICE_LABEL, null)) else crumbs(node, roots),
            // Volume rows are re-sorted by the LABEL they draw. [FolderSort.folders] orders by
            // `name`, which is the right key for directories and the wrong one here for the same
            // reason [rowLabel] exists: two volumes' display roots are routinely both `Music`, so
            // ordering by name leaves the two rows in whatever order the song list happened to
            // introduce the volumes — which changes when the library does.
            folders = if (volumeRows) folders.sortedWith(compareBy(FolderSort.NATURAL) { it.label })
            else folders,
            tracks = FolderSort.tracks(node.songs, state.sort, state.descending),
        )
    }

    /**
     * The label a directory row draws.
     *
     * **A top-level row is named after its VOLUME, never after its folder.** This is the join the
     * whole two-volume case turns on: elision folds each volume down to its first interesting
     * directory independently, and on a phone with an SD card that is routinely `Music` on BOTH —
     * so `node.name` gives the user two identical rows and no way to tell internal storage from the
     * card. `FolderViewModelTest` pins it with a two-volume fixture, which is the only evidence
     * this behaviour will ever have: no device on this fleet has a card.
     */
    private fun rowLabel(node: FolderNode, volumeRow: Boolean): String =
        if (volumeRow) volumeNames.label(node.volume) else node.name

    /**
     * `Internal storage › Music › Beck › Sea Change`, built from [FolderNode.segments] rather than
     * by walking the tree a second time.
     *
     * Depth 0 is the volume and carries [VolumeNames.label] — the same join [rowLabel] makes, for
     * the same reason.
     *
     * **Elided ancestors are rendered and INERT.** Rendered, because [FolderRoot.elided] is what
     * keeps elision from costing the user the truth about where they are. Inert, because there is
     * no destination for them: navigating to a level elision deliberately removed would push the
     * one-row screen the design exists to skip, and it is not on the back stack to be popped to, so
     * the pop-or-navigate fallback would GROW the stack rather than shrink it.
     */
    private fun crumbs(node: FolderNode, roots: List<FolderRoot>): List<FolderCrumb> {
        val displayDepth = roots.firstOrNull { it.displayRoot.volume == node.volume }
            ?.displayRoot?.segments?.size ?: 0
        val single = roots.size == 1
        return (0..node.segments.size).map { depth ->
            FolderCrumb(
                label = if (depth == 0) volumeNames.label(node.volume) else node.segments[depth - 1],
                route = when {
                    // The folder the user is looking at.
                    depth == node.segments.size -> null
                    // Above the display root: elided, so no destination exists.
                    depth < displayDepth -> null
                    // With one volume the display root IS the tab root.
                    depth == displayDepth && single -> Destinations.FOLDERS
                    else -> Destinations.folder(
                        FolderTree.folderKey(node.volume, node.segments.take(depth)),
                    )
                },
            )
        }
    }

    /**
     * The tab root: one volume's elided display root, or a synthetic node listing the volumes.
     *
     * The Unfiled bucket counts as a root here, so one stray location-less song puts a two-row
     * chooser at the top of the tab. That is the same instability [FolderTree.elideRoots] already
     * accepts and documents for a file dropped into `Download/` — one level at the top, truthfully
     * labelled — rather than a new one.
     */
    private fun homeNode(roots: List<FolderRoot>): FolderNode? = when (roots.size) {
        0 -> null
        1 -> roots.single().displayRoot
        else -> deviceNode(roots.map { it.displayRoot })
    }

    private fun deviceNode(volumes: List<FolderNode>) = FolderNode(
        key = DEVICE_KEY,
        volume = DEVICE_KEY,
        segments = emptyList(),
        name = DEVICE_LABEL,
        children = volumes,
        songs = emptyList(),
        deepSongCount = volumes.sumOf { it.deepSongCount },
        deepDurationMs = volumes.sumOf { it.deepDurationMs },
        deepFolderCount = volumes.size + volumes.sumOf { it.deepFolderCount },
    )

    /**
     * Searches from each volume's TRUE root — [FolderRoot.elided]'s first entry when elision
     * skipped anything — so a key naming an elided ancestor still resolves. Such a key is not
     * reachable from any link this app draws; it is reachable from a back stack restored after the
     * elision boundary moved, and resolving it beats reporting the folder gone.
     */
    private fun find(roots: List<FolderRoot>, key: String): FolderNode? =
        FolderTree.find(roots.map { it.elided.firstOrNull() ?: it.displayRoot }, key)

    // -------------------------------------------------------------------------------- the verbs

    fun setSort(sort: TrackSort) {
        viewModelScope.launch { settings.setFolderSort(sort) }
    }

    fun setDescending(descending: Boolean) {
        viewModelScope.launch { settings.setFolderSortDescending(descending) }
    }

    fun scan() {
        repo.requestScan()
    }

    /**
     * Play-in-context: the listed tracks become the queue.
     *
     * The queue is the folder's DIRECT tracks — what the screen is showing — not
     * [FolderSort.deepFlatten]. See [FolderNode]'s KDoc: the caption reports the deep counts while
     * "play this folder" is shallow. A folder with no direct tracks therefore offers no play button
     * at all rather than a button that silently plays a subfolder.
     *
     * Main-thread only, as [BrowseViewModel.play] is: [PlaybackConnection]'s commands are
     * `@MainThread`. Every caller is a Compose click handler.
     */
    @MainThread
    fun play(tracks: List<Song>, index: Int) {
        if (index in tracks.indices) connection.playFrom(tracks, index)
    }

    /** The listed tracks, shuffled. [random] is a parameter so a test runs the same call path. */
    @MainThread
    fun shuffle(tracks: List<Song>, random: Random = Random.Default) {
        if (tracks.isNotEmpty()) connection.playFrom(tracks.shuffled(random), 0)
    }
}
