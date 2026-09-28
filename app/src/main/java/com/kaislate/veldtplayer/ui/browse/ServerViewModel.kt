// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.account.ServerAccount
import com.kaislate.veldtplayer.data.account.ServerTypeNames
import com.kaislate.veldtplayer.data.account.ServerTypeStore
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.library.LibraryDerivations
import com.kaislate.veldtplayer.data.library.MusicRepository
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.library.sync.SubsonicSync
import com.kaislate.veldtplayer.data.net.OnlineCheck
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The server tab's three views of one library, in the order the segmented row shows them. */
enum class ServerSection(val label: String) {
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    SONGS("Songs"),
}

/** Whether the server tab exists, and what it is called. */
sealed interface ServerTabState {
    /**
     * Not read yet. Distinct from [Absent] because the nav host leaves the tab when it is absent,
     * and a restored back stack sitting on the tab must not be bounced to Songs in the frames
     * before the accounts table answers.
     */
    data object Loading : ServerTabState

    /** No server accounts: no tab. */
    data object Absent : ServerTabState

    /** [label] per [ServerTypeNames.tabLabel]; [accounts] in the Servers screen's order. */
    data class Present(val label: String, val accounts: List<ServerAccount>) : ServerTabState
}

/**
 * Whether the server tab exists and what it is called, as one flow for the two view models that
 * need it: [ServerViewModel] (the tab's content) and `TabsViewModel` (the bar's arrangement and
 * Settings → Tabs, which lists the server tab by its label). One derivation, so the bar and the
 * settings page can never name the tab differently.
 *
 * The accounts are read from [AccountDao] directly rather than through `AccountRepository
 * .observe`: that flow decrypts every account's secret per emission to answer `hasSecret`, and
 * the bar needs only ids and names — a Keystore round trip per account for a tab label would be
 * waste on every app start. Never emits [ServerTabState.Loading]; that is each collector's seed.
 */
class ServerTabs @Inject constructor(accountDao: AccountDao, serverTypes: ServerTypeStore) {
    val state: Flow<ServerTabState> =
        combine(accountDao.observeAll(), serverTypes.types) { rows, types ->
            val accounts = rows.map { ServerAccount(it.sourceId, it.displayName) }
            ServerTypeNames.tabLabel(accounts, types)
                ?.let { ServerTabState.Present(it, accounts) }
                ?: ServerTabState.Absent
        }
}

/**
 * The server tab (player-sheet/server-tab spec, Task B): whether it exists, its label, and the
 * library it shows — the songs server accounts contributed, narrowed to one account by the chip
 * row when there are several.
 *
 * Resolved at nav-host level, like the browse and playlist view models, so there is one instance
 * for the app's life: the chosen section and chip survive leaving the tab for an album and coming
 * back. [tab] comes from [ServerTabs]; the bar itself reads the same flow through
 * `TabsViewModel`, which also applies the user's order and hidden tabs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ServerViewModel @Inject constructor(
    serverTabs: ServerTabs,
    private val repo: MusicRepository,
    private val sync: SubsonicSync,
    private val connection: PlaybackConnection,
    private val online: OnlineCheck,
) : ViewModel() {

    val tab: StateFlow<ServerTabState> = serverTabs.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerTabState.Loading)

    private val chosenAccount = MutableStateFlow<String?>(null)

    /**
     * The chip row's choice — an account's `sourceId`, or null for "All". An account removed
     * while chosen reads as null here, so the view falls back to every remaining server rather
     * than to an empty list for an id nothing will ever sync again.
     */
    val selectedAccount: StateFlow<String?> = combine(chosenAccount, tab) { chosen, state ->
        chosen?.takeIf { id -> (state as? ServerTabState.Present)?.accounts?.any { it.sourceId == id } == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _section = MutableStateFlow(ServerSection.ALBUMS)

    /** Which of Albums · Artists · Songs is showing. Albums first, per the spec: a server
     *  library is browsed by record far more than by track. */
    val section: StateFlow<ServerSection> = _section.asStateFlow()

    /** The accounts the view currently covers: the chosen one, or all of them. */
    private val sourceIds: Flow<Set<String>> = combine(tab, selectedAccount) { state, chosen ->
        val accounts = (state as? ServerTabState.Present)?.accounts.orEmpty()
        if (chosen != null) setOf(chosen) else accounts.mapTo(HashSet()) { it.sourceId }
    }.distinctUntilChanged()

    /**
     * The covered accounts' songs, or null until the first read lands. Null rather than an empty
     * seed so the screen can tell "not read yet" from "this server has nothing" — the second is a
     * message, the first must not flash one.
     */
    val songs: StateFlow<List<Song>?> = sourceIds
        .flatMapLatest { ids -> repo.songsFrom(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Derived from [songs] exactly as `MusicRepository.albums` derives the whole library's. */
    val albums: StateFlow<List<Album>> = songs
        .map { LibraryDerivations.deriveAlbums(it.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = songs
        .map { LibraryDerivations.deriveArtists(it.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True while any covered account's sync is pending or running — what turns an empty view
     * into "Syncing with …" rather than "Nothing from … yet".
     *
     * Seeded TRUE for the reason `BrowseViewModel.scanning` is: the view is only ever empty in
     * practice right after an account was added, which always requests a sync, so assuming one is
     * coming is right in the case that matters and costs one frame of spinner in the rare other.
     */
    val syncing: StateFlow<Boolean> = anySyncRunning()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _refreshing = MutableStateFlow(false)

    /**
     * The pull-to-refresh indicator: on for exactly the life of one [pullToRefresh] — see
     * [ServerRefresh] for when that ends. Round 4 read the syncs' own running state here, which
     * counts a sync queued behind its network constraint, and so spun forever offline.
     */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** One-shot snackbar text from a pull-to-refresh ("You're offline…", "Couldn't reach
     *  Navidrome."), collected by the nav host into the app's one snackbar host. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val refresher = ServerRefresh(
        isOnline = online::isOnline,
        request = sync::request,
        status = sync::status,
    )
    private var refreshJob: Job? = null

    private fun anySyncRunning(): Flow<Boolean> = sourceIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(false)
            } else {
                combine(ids.map { sync.status(it) }) { statuses -> statuses.any { it.running } }
            }
        }

    fun selectAccount(sourceId: String?) {
        chosenAccount.value = sourceId
    }

    fun selectSection(section: ServerSection) {
        _section.value = section
    }

    /** The empty state's "Sync now": every covered account, each deduped by its own unique work. */
    fun syncNow() {
        coveredAccounts()?.accounts?.forEach { sync.request(it.sourceId) }
    }

    /**
     * The tab's pull-to-refresh: sync the covered accounts through [ServerRefresh], with
     * [refreshing] on for exactly as long as it runs and its message, if any, sent to [messages].
     * A second pull while one is still running is ignored rather than stacked.
     */
    fun pullToRefresh() {
        if (refreshJob?.isActive == true) return
        val covered = coveredAccounts() ?: return
        _refreshing.value = true
        refreshJob = viewModelScope.launch {
            try {
                // A lone account's failure is named by the tab label ("Couldn't reach Navidrome.");
                // with several, the label may be "Servers", so each account names itself.
                val message = refresher.refresh(covered.accounts) { account ->
                    if (covered.total == 1) covered.label else account.displayName
                }
                if (message != null) _messages.emit(message)
            } finally {
                _refreshing.value = false
            }
        }
    }

    private class Covered(val accounts: List<ServerAccount>, val label: String, val total: Int)

    /** The accounts the view covers now (the chosen chip, or all), with the tab's label. */
    private fun coveredAccounts(): Covered? {
        val state = tab.value as? ServerTabState.Present ?: return null
        val chosen = selectedAccount.value
        return Covered(
            accounts = state.accounts.filter { chosen == null || it.sourceId == chosen },
            label = state.label,
            total = state.accounts.size,
        )
    }

    /**
     * Play-in-context, the server-filtered list as the queue — through `playFrom`, the same entry
     * point every other list tap uses, so it opens the player the same way. Main thread only; see
     * `BrowseViewModel.play`.
     */
    fun play(songs: List<Song>, index: Int) = connection.playFrom(songs, index)
}
