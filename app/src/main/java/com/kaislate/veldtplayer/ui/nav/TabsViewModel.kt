// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.data.settings.StoredTabs
import com.kaislate.veldtplayer.ui.browse.ServerTabState
import com.kaislate.veldtplayer.ui.browse.ServerTabs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The bar's arrangement right now: the stored intent, resolved against whether a server exists.
 * Every list here is already through [TabArrangement], so a reader never re-applies a rule.
 */
data class TabsState(
    val stored: StoredTabs,
    /** The server tab's label, or null while no server account exists. */
    val serverLabel: String?,
    /** Every tab that can exist now, hidden ones included, in the user's order — Settings' list. */
    val available: List<String>,
    /** The bar's tabs, in order. Never empty. */
    val shown: List<String>,
    /** Where the app opens, after the hidden/vanished fallback. */
    val start: String,
) {
    val serverPresent: Boolean get() = serverLabel != null

    fun canHide(id: String): Boolean = TabArrangement.canHide(stored, id, serverPresent)
}

/**
 * The bottom tabs' arrangement (player-sheet/server-tab spec, Round 2 → D) for the two surfaces
 * that edit it — the bar's long-press drag and Settings → Tabs — and the nav host, which reads
 * [state] for the bar and the start destination.
 *
 * Every write reads [state]'s CURRENT value and writes a whole new order or hidden set. There is
 * no merge with a concurrent write because there is no concurrent writer: both editing surfaces
 * are driven by one finger.
 */
@HiltViewModel
class TabsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    serverTabs: ServerTabs,
) : ViewModel() {

    /**
     * Null until BOTH the stored arrangement and the accounts table have answered — the nav host
     * waits on it before choosing its start destination, because an "Open on" of the server tab
     * cannot be honoured, or refused, before it is known whether a server exists.
     */
    val state: StateFlow<TabsState?> = combine(settings.tabs, serverTabs.state) { stored, server ->
        val present = server is ServerTabState.Present
        TabsState(
            stored = stored,
            serverLabel = (server as? ServerTabState.Present)?.label,
            available = TabArrangement.available(stored, present),
            shown = TabArrangement.shown(stored, present),
            start = TabArrangement.startTab(stored, present),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The bar's long-press drag dropped: [shownOrder] is the bar's new left-to-right order. Only
     * those slots move; hidden tabs and an absent server tab keep theirs (see
     * [TabArrangement.reorderSubset]).
     */
    fun reorderShown(shownOrder: List<String>) = writeOrder(shownOrder)

    /** Settings → Tabs moved the row at [from] to [to], indices into [TabsState.available]. */
    fun move(from: Int, to: Int) {
        val current = state.value ?: return
        writeOrder(TabArrangement.moved(current.available, from, to))
    }

    fun setShown(id: String, shown: Boolean) {
        val current = state.value ?: return
        val next = TabArrangement.withHidden(current.stored, id, hidden = !shown, current.serverPresent)
        if (next != current.stored) viewModelScope.launch { settings.setHiddenTabs(next.hidden) }
    }

    fun setStart(id: String) {
        viewModelScope.launch { settings.setStartTab(id) }
    }

    fun reset() {
        viewModelScope.launch { settings.resetTabs() }
    }

    private fun writeOrder(subset: List<String>) {
        val current = state.value ?: return
        val full = TabArrangement.normalizedOrder(current.stored.order)
        val next = TabArrangement.reorderSubset(full, subset)
        if (next != full) viewModelScope.launch { settings.setTabOrder(next) }
    }
}
