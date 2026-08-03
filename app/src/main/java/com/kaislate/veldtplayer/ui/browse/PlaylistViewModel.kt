// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.playlist.PlaylistRepository
import com.kaislate.veldtplayer.data.playlist.m3u.DocumentNameReader
import com.kaislate.veldtplayer.data.playlist.m3u.PlaylistImporter
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * The playlists tab and one playlist's page.
 *
 * Separate from [BrowseViewModel] rather than bolted onto it: nothing here reads the library
 * derivations that class exists to hold, and a playlist page is reached from its own destination.
 * It holds NO `MediaController` — playback goes through the injected [PlaybackConnection] (global
 * constraint 6).
 *
 * Every decision it makes is delegated to [PlaylistPresentation], which is where the tests are.
 * What is left here is plumbing: which flows to combine, and when to re-resolve.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val importer: PlaylistImporter,
    private val documentNames: DocumentNameReader,
    private val music: MusicRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {

    /**
     * The tab's list.
     *
     * Combined with [MusicRepository.songs] and not just the playlist table, because a playlist's
     * caption changes when the LIBRARY does: a rescan that finds the four files a playlist was
     * missing has to turn "47 tracks · 4 missing" into "47 tracks" without the user reopening
     * anything. `resolve()` is what re-links them, and it is a suspend read, so the combine feeds
     * `mapLatest` — a scan that emits twice in quick succession cancels the first resolve rather
     * than queueing a second full pass.
     *
     * Seeded [PlaylistsUiState.Loading], which is the ONLY place that state comes from. See
     * [PlaylistPresentation.listStateOf].
     */
    val state: StateFlow<PlaylistsUiState> =
        combine(playlists.observe(), music.songs()) { entities, _ -> entities }
            .mapLatest { entities ->
                PlaylistPresentation.listStateOf(
                    entities.map { PlaylistPresentation.cardOf(it, playlists.resolve(it.id)) },
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PlaylistsUiState.Loading,
            )

    /**
     * One playlist's page: its name, its rows and its covers, re-derived whenever its entries move
     * or the library changes underneath them.
     *
     * A cold flow per call, like [BrowseViewModel.songsForAlbum] — the caller `remember`s it on the
     * playlist id so a recomposition does not restart the collection.
     *
     * The playlist table is observed as well as its entries so that a playlist DELETED from another
     * surface collapses this screen to [PlaylistDetailUiState.Missing] instead of leaving a page
     * for a row that is gone.
     */
    fun detail(playlistId: Long): Flow<PlaylistDetailUiState> =
        combine(
            playlists.observe(),
            playlists.observeEntries(playlistId),
            music.songs(),
        ) { all, _, _ -> all.firstOrNull { it.id == playlistId } }
            .mapLatest { entity ->
                PlaylistPresentation.detailStateOf(
                    playlist = entity,
                    tracks = if (entity == null) emptyList() else playlists.resolve(playlistId),
                )
            }

    // ------------------------------------------------------------------------------- importing

    private val _importOutcome = MutableStateFlow<ImportOutcome?>(null)

    /**
     * The last import's result, success or failure, held until [dismissImport].
     *
     * State, not an event: "Imported 43 of 47" with the four named is the only chance the user gets
     * to learn that a playlist arrived incomplete, and a message that vanishes on its own is a
     * message half of them will never read.
     */
    val importOutcome: StateFlow<ImportOutcome?> = _importOutcome.asStateFlow()

    private val _importing = MutableStateFlow(false)

    /** True while a document is being read and resolved. Gates the affordance against re-entry. */
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /**
     * Import the document the picker returned.
     *
     * The whole chain — naming included — runs inside [PlaylistPresentation.importOutcome], because
     * both halves can throw the SAME [SecurityException] when the grant has lapsed, and neither is
     * caught anywhere below. That is a confirmed crash, not a hypothetical one: a user who picks a
     * playlist after the app's process was killed hits it having done nothing wrong.
     */
    fun import(uri: String) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            val outcome = try {
                PlaylistPresentation.importOutcome {
                    importer.import(uri, PlaylistNaming.of(documentNames.displayName(uri), uri))
                }
            } finally {
                // `finally`, and BEFORE the report is published. Finally, because a cancelled
                // import must not leave the affordance disabled for the rest of the screen's
                // life. Before, so that the moment a report exists the spinner is already gone —
                // the two would otherwise be true together for a frame, and the screen would
                // show a progress indicator over a finished result.
                _importing.value = false
            }
            _importOutcome.value = outcome
        }
    }

    fun dismissImport() {
        _importOutcome.value = null
    }

    /**
     * The document picker itself would not open.
     *
     * Rare, but not impossible — a stripped AOSP build with no `DocumentsUI` throws
     * `ActivityNotFoundException` out of `launch()`, on the UI thread, where it is a crash. It is
     * reported through the same surface as every other import failure rather than a second one.
     *
     * Its OWN cause, not [ImportFailure.UNEXPECTED]. No file was ever chosen, so copy about "that
     * file" is a lie — and it is the one failure whose retry can never succeed, which is why
     * [PlaylistImportReport.retryable] singles it out.
     */
    fun reportPickerUnavailable() {
        _importOutcome.value = ImportOutcome.Failed(ImportFailure.PICKER_UNAVAILABLE)
    }

    // ------------------------------------------------------------------------------- messages

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /**
     * One-shot confirmations: "Added 12 tracks to “Road Trip”", "Added 36 tracks to the queue".
     *
     * A SharedFlow and not a StateFlow, unlike [importOutcome], and the difference is deliberate.
     * An import report is the only record of which tracks did not arrive and must survive until it
     * is read; an append confirmation is an acknowledgement of something the user just did on
     * purpose, and holding it as state would re-show it on every recomposition and every return to
     * the screen. `extraBufferCapacity` so `tryEmit` from a non-suspending caller cannot drop one.
     */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ------------------------------------------------------------------------------- creating

    /**
     * Create a playlist and, if the user got here from a browse surface, fill it in the same
     * breath.
     *
     * The two halves are ONE operation on purpose. Task 6 declined to ship a create affordance
     * precisely because a playlist that can only be born empty is a dead end, and a create that
     * returned before adding would leave the same dead end open for as long as the add took —
     * including forever, if it failed.
     */
    fun create(name: String, addition: PlaylistAddition = PlaylistAddition.NOTHING) {
        val cleaned = PlaylistNaming.sanitize(name) ?: return
        viewModelScope.launch {
            val id = playlists.create(cleaned)
            if (!addition.isEmpty) playlists.addSongs(id, addition.songs)
            _messages.tryEmit(PlaylistAdditions.createdMessage(cleaned, addition))
        }
    }

    /**
     * Add a browse surface's selection to an existing playlist.
     *
     * [name] is passed rather than looked up because the caller has the row the user tapped, and
     * re-reading the name here would be a second read of a table that may already have changed.
     */
    fun addTo(playlistId: Long, name: String, addition: PlaylistAddition) {
        if (addition.isEmpty) return
        viewModelScope.launch {
            playlists.addSongs(playlistId, addition.songs)
            _messages.tryEmit(PlaylistAdditions.addedMessage(name, addition))
        }
    }

    // ------------------------------------------------------------------------------- mutations

    /** Renames, unless the user cleared the field — see [PlaylistNaming.sanitize]. */
    fun rename(playlistId: Long, name: String) {
        val cleaned = PlaylistNaming.sanitize(name) ?: return
        viewModelScope.launch { playlists.rename(playlistId, cleaned) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { playlists.delete(playlistId) }
    }

    /** Commits a finished drag. The repository renumbers the whole sequence in one transaction. */
    fun move(playlistId: Long, from: Int, to: Int) {
        if (from == to) return
        viewModelScope.launch { playlists.move(playlistId, from, to) }
    }

    /** Removes one entry — including an unresolved one, which is the only way to be rid of it. */
    fun removeEntry(entryId: Long) {
        viewModelScope.launch { playlists.remove(entryId) }
    }

    // ------------------------------------------------------------------------------- playback

    /**
     * Play the playlist from the tapped row.
     *
     * The queue is the playlist's resolvable tracks and the start index is counted in THAT list —
     * see [PlaylistPresentation.playTarget]. A row that resolves to nothing plays nothing.
     *
     * Main-thread only, as [BrowseViewModel.play] is: [PlaybackConnection]'s commands are
     * `@MainThread`. Every caller is a Compose click handler.
     */
    @MainThread
    fun play(rows: List<PlaylistTrackRow>, index: Int) {
        val target = PlaylistPresentation.playTarget(rows, index) ?: return
        connection.playFrom(target.queue, target.startIndex)
    }

    /** The whole playlist from the top. */
    @MainThread
    fun playAll(rows: List<PlaylistTrackRow>) {
        val target = PlaylistPresentation.playAllTarget(rows) ?: return
        connection.playFrom(target.queue, target.startIndex)
    }

    /**
     * The whole playlist, shuffled.
     *
     * [random] is a parameter with a default rather than a field, so the shuffle a test asks for is
     * the same call the screen makes — see [PlaylistPresentation.shuffleTarget], which is where the
     * property that unresolved entries cannot reach the queue is actually pinned.
     */
    @MainThread
    fun shuffle(rows: List<PlaylistTrackRow>, random: Random = Random.Default) {
        val target = PlaylistPresentation.shuffleTarget(rows, random) ?: return
        connection.playFrom(target.queue, target.startIndex)
    }

    /**
     * Append the playlist's playable tracks to whatever is queued, and say how many that was.
     *
     * The queue AND the message come from one [PlaylistPresentation.actionsOf] call, so the number
     * the user reads is by construction the number of tracks that were handed to the player.
     */
    @MainThread
    fun appendToQueue(rows: List<PlaylistTrackRow>) {
        val actions = PlaylistPresentation.actionsOf(rows)
        if (actions.queue.isNotEmpty()) connection.addToQueue(actions.queue)
        _messages.tryEmit(actions.appendedMessage)
    }
}
