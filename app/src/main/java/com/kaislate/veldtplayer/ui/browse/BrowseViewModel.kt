package com.kaislate.veldtplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

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
     * The term [answered], [resultAlbums] and [resultArtists] are being computed FOR: the
     * field text, trimmed, once it has stopped changing for [SEARCH_DEBOUNCE_MS].
     *
     * Public rather than private because "nothing matched" is only ever true of a SETTLED
     * term. A screen that compares this against [query] can tell an empty result set apart
     * from a query that has not run yet, and so does not flash "No matches for b" at every
     * fast typist. It is also the term the empty message should quote back.
     */
    val settledQuery: StateFlow<String> = _query
        .settleSearchTerms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * Songs matching [settledQuery] (title/artist/album substring), **tagged with the term
     * they answer**.
     *
     * The tag is why this exists rather than [results] alone. [settledQuery] closes the
     * debounce window but not the Room one: `repo.search` answers on the Room query
     * executor, so for the length of one query the rows here still describe the PREVIOUS
     * term while the pure shelves below have already moved on. Reading "settled, and
     * nothing here" as a verdict would then announce "No matches" about a query still in
     * flight — the same false negative [settledQuery] exists to prevent, one stage later.
     * Comparing [SearchAnswer.term] against [settledQuery] shuts that window too; see
     * [searchPending].
     *
     * `internal`, unlike the rest of the search surface: [SearchAnswer] is the pipeline's
     * own vocabulary, not part of the published contract, and the only consumer that has to
     * reason about the query window is the search screen next door.
     */
    internal val answered: StateFlow<SearchAnswer> = settledQuery
        .answerSearch(repo::search)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchAnswer.NONE)

    /**
     * Songs matching [settledQuery] — the published rows-only view of [answered].
     *
     * A blank term yields nothing rather than the whole library — a search screen that
     * dumps every song before you type reads as broken. Anything that has to decide
     * whether an empty list is a VERDICT must read [answered] instead and take both the
     * rows and the term from that one value: these are two independently-collected
     * `stateIn`s, so pairing this list with a term read from elsewhere reopens exactly the
     * window [answered] was introduced to close.
     */
    val results: StateFlow<List<Song>> = answered
        .map { it.songs }
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
