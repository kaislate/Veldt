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
import com.kaislate.veldtplayer.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * The server tab (player-sheet/server-tab spec, Task B): whether it exists, its label, and the
 * library it shows — the songs server accounts contributed, narrowed to one account by the chip
 * row when there are several.
 *
 * Resolved at nav-host level, like the browse and playlist view models, because two surfaces
 * read it: the bottom bar needs [tab] on every destination, and the tab's own screen needs the
 * rest. One instance also means the chosen section and chip survive leaving the tab for an album
 * and coming back.
 *
 * The accounts are read from [AccountDao] directly rather than through `AccountRepository
 * .observe`: that flow decrypts every account's secret per emission to answer `hasSecret`, and
 * the bar needs only ids and names — a Keystore round trip per account for a tab label would be
 * waste on every app start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ServerViewModel @Inject constructor(
    accountDao: AccountDao,
    serverTypes: ServerTypeStore,
    private val repo: MusicRepository,
    private val sync: SubsonicSync,
    private val connection: PlaybackConnection,
) : ViewModel() {

    val tab: StateFlow<ServerTabState> =
        combine(accountDao.observeAll(), serverTypes.types) { rows, types ->
            val accounts = rows.map { ServerAccount(it.sourceId, it.displayName) }
            ServerTypeNames.tabLabel(accounts, types)
                ?.let { ServerTabState.Present(it, accounts) }
                ?: ServerTabState.Absent
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerTabState.Loading)

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
    val syncing: StateFlow<Boolean> = sourceIds
        .flatMapLatest { ids ->
            if (ids.isEmpty()) {
                flowOf(false)
            } else {
                combine(ids.map { sync.status(it) }) { statuses -> statuses.any { it.running } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun selectAccount(sourceId: String?) {
        chosenAccount.value = sourceId
    }

    fun selectSection(section: ServerSection) {
        _section.value = section
    }

    /** The empty state's "Sync now": every covered account, each deduped by its own unique work. */
    fun syncNow() {
        val state = tab.value as? ServerTabState.Present ?: return
        val chosen = selectedAccount.value
        state.accounts
            .filter { chosen == null || it.sourceId == chosen }
            .forEach { sync.request(it.sourceId) }
    }

    /**
     * Play-in-context, the server-filtered list as the queue — through `playFrom`, the same entry
     * point every other list tap uses, so it opens the player the same way. Main thread only; see
     * `BrowseViewModel.play`.
     */
    fun play(songs: List<Song>, index: Int) = connection.playFrom(songs, index)
}
