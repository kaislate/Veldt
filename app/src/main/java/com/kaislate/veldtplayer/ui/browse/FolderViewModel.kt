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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * Spelled with the `\u0000` ESCAPE, exactly as [UNFILED_KEY] is one file over. A raw NUL typed into
 * the literal is invisible in a diff and easy for tooling to mangle into a space, which would
 * quietly turn the sentinel into an ordinary string a real volume could collide with.
 */
internal const val DEVICE_KEY: String = "\u0000device"

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
    /**
     * What to CALL this folder in a verb that has to name it — a playlist sheet headed
     * `Add 42 tracks from “Sea Change”`.
     *
     * Not [FolderNode.name], and the difference is the same join [FolderRowItem.label] exists for:
     * a volume's display root is routinely named `Music` on both internal storage and an SD card,
     * so a sheet headed `Add 42 tracks from “Music”` names neither of them. This is the LAST
     * crumb's label, which is already the volume label at depth 0 and the directory name below it —
     * one derivation, not a second rule that can drift from the breadcrumb the user is reading.
     *
     * Derived here rather than by the screen calling `crumbs.last()`: that call is safe only under
     * an invariant held in this file (crumbs is never empty), and an invariant a composable depends
     * on from outside is one refactor away from a crash on the tab root.
     */
    val subject: String = "",
)

/** The listing a key that names nothing produces. */
private val NO_LISTING = FolderListing(null, emptyList(), emptyList(), emptyList(), "")

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
        val crumbs = if (volumeRows) listOf(FolderCrumb(DEVICE_LABEL, null)) else crumbs(node, roots)
        return FolderListing(
            node = node,
            crumbs = crumbs,
            // Volume rows are re-sorted by the LABEL they draw. [FolderSort.folders] orders by
            // `name`, which is the right key for directories and the wrong one here for the same
            // reason [rowLabel] exists: two volumes' display roots are routinely both `Music`, so
            // ordering by name leaves the two rows in whatever order the song list happened to
            // introduce the volumes — which changes when the library does.
            folders = if (volumeRows) folders.sortedWith(compareBy(FolderSort.NATURAL) { it.label })
            else folders,
            tracks = FolderSort.tracks(node.songs, state.sort, state.descending),
            subject = crumbs.last().label,
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

    /**
     * One-shot confirmations for the verb that leaves nothing on screen: "Added 42 tracks to the
     * queue".
     *
     * Appending is the only folder verb with no visible result of its own — the current track keeps
     * playing and the new tracks are below the fold — so the message IS the feedback. Collected in
     * `VeldtNavHost` beside [PlaylistViewModel.messages], into the same snackbar host, for the
     * reason recorded there: one collector, so no screen can swallow it.
     *
     * A SharedFlow and not a StateFlow, for the reason [PlaylistViewModel.messages] records — an
     * acknowledgement held as state re-shows itself on every recomposition and on every return to
     * the screen. `extraBufferCapacity` so `tryEmit` from a non-suspending caller cannot drop one.
     */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
     * Play-in-context: the tapped list becomes the queue, starting where it was tapped.
     *
     * The list is the caller's — a track row hands over [FolderListing.tracks], the folder verbs
     * below hand over whichever scope they name. Nothing here decides the SCOPE; that decision is
     * [FolderScope]'s and is made once, in [folderTracks].
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

    // ------------------------------------------------------------------------- the folder verbs

    /**
     * The list a folder verb acts on, for either scope. **Every folder verb goes through here.**
     *
     * That is the [PlaylistActions] lesson applied one surface over: the queue and the sentence
     * that quotes its size are produced from ONE list in one call, so a confirmation claiming 42
     * tracks over a queue of 12 is not expressible. No caller may re-derive either scope.
     *
     * Takes the [state] rather than reading [state] itself, for the same reason [listing] does —
     * the screen already holds the emission the user is looking at, and a verb that re-read the
     * flow could act on a tree that arrived between the long press and the menu tap.
     */
    fun folderTracks(state: FolderUiState, node: FolderNode, scope: FolderScope): List<Song> =
        when (scope) {
            FolderScope.THIS_FOLDER -> FolderSort.tracks(node.songs, state.sort, state.descending)
            FolderScope.WITH_SUBFOLDERS ->
                FolderSort.deepFlatten(node, state.sort, state.descending)
        }

    /**
     * The header's PRIMARY action: this folder and everything under it, depth-first pre-order.
     *
     * Deep and not direct — owner decision, 2026-08-13. The folder a user opens is usually the
     * record, and `Disc 1`/`Disc 2` under it are its parts; a primary play that stopped at the
     * record's own (empty) direct list would be a button that does nothing on exactly the tree this
     * feature exists to repair.
     */
    @MainThread
    fun playFolderDeep(state: FolderUiState, node: FolderNode) =
        play(folderTracks(state, node, FolderScope.WITH_SUBFOLDERS), 0)

    /** The secondary action: what the screen is listing, and nothing below it. */
    @MainThread
    fun playFolderShallow(state: FolderUiState, node: FolderNode) =
        play(folderTracks(state, node, FolderScope.THIS_FOLDER), 0)

    /**
     * A folder, shuffled.
     *
     * [random] is a parameter with a default rather than a field, so the call a test makes is the
     * call the screen makes — the same shape [PlaylistViewModel.shuffle] uses.
     */
    @MainThread
    fun shuffleFolder(
        state: FolderUiState,
        node: FolderNode,
        scope: FolderScope,
        random: Random = Random.Default,
    ) = shuffle(folderTracks(state, node, scope), random)

    /**
     * Append a folder to whatever is queued, and say how many tracks that was.
     *
     * The count in the message is `tracks.size` of the very list handed to [PlaybackConnection] —
     * never `node.deepSongCount`, which is the aggregate the CAPTION quotes and is computed by a
     * different walk. They agree today; a count taken from the node rather than from the queue is
     * the defect [PlaylistActions] exists to make unrepresentable, and it must not be reintroduced
     * here because the number happens to match.
     *
     * `skipped = 0` is a fact rather than a placeholder: a folder's tracks come out of the tree,
     * which is derived from the song list itself, so unlike a playlist there is no such thing as an
     * entry that resolves to nothing.
     */
    @MainThread
    fun addFolderToQueue(state: FolderUiState, node: FolderNode, scope: FolderScope) {
        val tracks = folderTracks(state, node, scope)
        if (tracks.isEmpty()) return
        connection.addToQueue(tracks)
        _messages.tryEmit(PlaylistPresentation.appendedMessage(tracks.size, skipped = 0))
    }

    /**
     * What a folder contributes to a playlist: a SNAPSHOT of its tracks in the order shown.
     *
     * [subject] is passed rather than read off [FolderNode.name] because a top-level folder is
     * named after its VOLUME — see [FolderListing.subject] and [rowLabel]. The header hands over
     * `listing.subject`; a row's long-press menu hands over `FolderRowItem.label`. Both are the
     * label already on screen, so the sheet names what the user actually pressed.
     */
    fun folderAddition(
        state: FolderUiState,
        node: FolderNode,
        subject: String,
        scope: FolderScope,
    ): PlaylistAddition =
        PlaylistAdditions.ofFolder(subject, folderTracks(state, node, scope))
}

/**
 * Which tracks a folder verb acts on.
 *
 * Two scopes and not one, because both answers to "play this folder" are honest and a user in an
 * `Album/Disc 1`, `Album/Disc 2` tree wants each at different moments. Keeping them as one enum
 * rather than as two pairs of methods is what makes the queue verb and the playlist verb reach both
 * scopes without a third notion of "the folder's tracks" appearing anywhere.
 *
 * The screen offers the second scope ONLY where the two differ — a leaf folder has no subfolders,
 * so a menu listing both would charge the user a decision between two identical outcomes.
 */
enum class FolderScope {
    /** This folder's direct tracks only, in the current sort order. [FolderSort.tracks]. */
    THIS_FOLDER,

    /** This folder and every descendant, depth-first pre-order. [FolderSort.deepFlatten]. */
    WITH_SUBFOLDERS,
}
