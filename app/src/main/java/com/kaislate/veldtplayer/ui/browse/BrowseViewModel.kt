package com.kaislate.veldtplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * How long the field rests before its text becomes a query. Short enough that results feel
 * live, long enough that a fast typist costs one Room query instead of one per keystroke.
 */
private const val SEARCH_DEBOUNCE_MS = 180L

/**
 * One ViewModel for every browse surface. It holds NO MediaController — playback goes
 * through the injected [PlaybackConnection] (global constraint 6).
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val repo: MusicRepository,
    private val connection: PlaybackConnection,
) : ViewModel() {

    val songs: StateFlow<List<Song>> = repo.songs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = repo.albums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = repo.artists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True while a library scan is pending or running.
     *
     * Seeded TRUE, not false. The nav host enqueues a scan the instant audio access is
     * granted, but WorkManager reports back asynchronously — so a false seed would render
     * "No songs yet" for the frames before the first emission lands, which is precisely
     * the lie this flow exists to stop telling. Assuming a scan is coming is right in
     * every reachable case, because the only screen that reads this is only reached with
     * access granted, and access granted always enqueues a scan.
     */
    val scanning: StateFlow<Boolean> = repo.scanning()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Playback failures (unreadable/deleted files) surfaced for the snackbar, with a
     * skip-through of a dead queue collapsed into one message — see [batchPlaybackErrors].
     */
    val errors: Flow<String> = connection.errors.batchPlaybackErrors()

    // ---- Search ---------------------------------------------------------------------

    private val _query = MutableStateFlow("")

    /** The live field text, echoed straight back to the text field the user is typing in. */
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    /**
     * The term [results], [resultAlbums] and [resultArtists] actually describe: the field
     * text, trimmed, once it has stopped changing for [SEARCH_DEBOUNCE_MS].
     *
     * Public rather than private because "nothing matched" is only ever true of a SETTLED
     * term. A screen that compares this against [query] can tell an empty result set apart
     * from a query that has not run yet, and so does not flash "No matches for b" at every
     * fast typist. It is also the term the empty message should quote back.
     */
    @OptIn(FlowPreview::class)
    val settledQuery: StateFlow<String> = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .map { it.trim() }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Songs matching [settledQuery], title/artist/album substring.
     *
     * A blank term yields nothing rather than the whole library — a search screen that
     * dumps every song before you type reads as broken.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<Song>> = settledQuery
        .flatMapLatest { term -> if (term.isBlank()) flowOf(emptyList()) else repo.search(term) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Albums and artists the term NAMES — filtered from the real catalogue rather than
     * derived from [results].
     *
     * Deriving them from the matched songs (one query covering all three sections) is
     * cheaper to write and wrong to read: "love" matches forty songs spread over thirty
     * records, and an Artists section then lists thirty artists with no "love" anywhere in
     * their name. It also hands the sections partial counts — an album whose twelve tracks
     * matched three would be captioned "3 songs". Filtering [albums]/[artists], which are
     * already derived once for the tabs, costs no extra query and keeps both honest.
     */
    val resultAlbums: StateFlow<List<Album>> =
        combine(settledQuery, albums) { term, all -> SearchFilter.albums(all, term) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** See [resultAlbums] for why these are filtered rather than derived from [results]. */
    val resultArtists: StateFlow<List<Artist>> =
        combine(settledQuery, artists) { term, all -> SearchFilter.artists(all, term) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun songsForAlbum(key: String): Flow<List<Song>> = repo.songsForAlbum(key)

    fun songsForArtist(key: String): Flow<List<Song>> = repo.songsForArtist(key)

    fun scan() {
        repo.requestScan()
    }

    /**
     * Play-in-context: the tapped list becomes the queue.
     *
     * Main-thread only, because [PlaybackConnection]'s commands are `@MainThread`
     * (its pending-command deque is unsynchronized and `MediaController` verifies its
     * application thread). Every caller is a Compose click handler, so this holds.
     */
    fun play(songs: List<Song>, index: Int) = connection.playFrom(songs, index)
}
