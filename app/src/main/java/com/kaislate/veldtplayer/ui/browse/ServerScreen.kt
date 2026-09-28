// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.account.ServerAccount
import com.kaislate.veldtplayer.ui.theme.neutralPalette

/** Gap between the chip row, the segmented row, and the content below them. */
private val HEADER_GAP = 8.dp

/**
 * The server tab (player-sheet/server-tab spec, Task B §5): only the music server accounts
 * contributed, as Albums · Artists · Songs, with a chip per account once there is more than one.
 *
 * **Built from the main tabs' own parts** — [AlbumGrid], [ArtistList], [SongList], the same
 * long-press playlist sheet, the same detail screens behind a tap — so this reads as the same
 * library through a filter, not as a second browser with its own habits. The main tabs are
 * untouched: server music still appears in them too (spec §6).
 *
 * The detail screens a tap opens are keyed by album/artist, not by source, so they show every
 * source's songs under that key. Accepted in the spec: a record the user owns locally AND streams
 * is one record.
 *
 * Nothing is drawn while the tab is [ServerTabState.Loading] or [ServerTabState.Absent]: the nav
 * host leaves this destination when the last account goes, and until the accounts table has
 * answered there is nothing honest to show.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    vm: ServerViewModel,
    playlistVm: PlaylistViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle throughout — every flow here is WhileSubscribed, see SongsScreen.
    val tab by vm.tab.collectAsStateWithLifecycle()
    val present = tab as? ServerTabState.Present ?: return
    val selectedAccount by vm.selectedAccount.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()

    // Held above the lists for the reason SongsScreen holds it there: the sheet must survive its
    // row scrolling away.
    var pendingAddition by remember { mutableStateOf<PlaylistAddition?>(null) }
    AddToPlaylistHost(
        vm = playlistVm,
        addition = pendingAddition,
        onDismiss = { pendingAddition = null },
    )

    // The header takes the top and side insets as padding, like every tab's list does; the list
    // below it keeps only the bottom inset, as contentPadding, so it still scrolls beneath the
    // translucent navigation bar.
    val direction = LocalLayoutDirection.current
    val listPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            ),
    ) {
        // Only with more than one account: a single "All" chip beside a single account chip would
        // be two names for the same view.
        if (present.accounts.size > 1) {
            AccountChips(
                accounts = present.accounts,
                selected = selectedAccount,
                onSelect = vm::selectAccount,
            )
        }
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SIDE_MARGIN, vertical = HEADER_GAP),
        ) {
            ServerSection.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = entry == section,
                    onClick = { vm.selectSection(entry) },
                    shape = SegmentedButtonDefaults.itemShape(index, ServerSection.entries.size),
                ) {
                    Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Pull to sync the account(s) shown (round 4). The indicator follows the PULL, not the
        // work (round 5): it stops at once offline, when the requested syncs finish, or after
        // 30 s, with a snackbar saying which — see ServerRefresh.
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::pullToRefresh,
            modifier = Modifier.weight(1f),
        ) {
            val loaded = songs ?: return@PullToRefreshBox
            if (loaded.isEmpty()) {
                val name = present.accounts.firstOrNull { it.sourceId == selectedAccount }?.displayName
                    ?: present.accounts.singleOrNull()?.displayName
                    ?: "your servers"
                ServerEmpty(name = name, syncing = syncing, onSync = vm::syncNow, contentPadding = listPadding)
                return@PullToRefreshBox
            }
            when (section) {
                ServerSection.ALBUMS -> AlbumGrid(
                    albums = albums,
                    songs = loaded,
                    onOpenAlbum = onOpenAlbum,
                    contentPadding = listPadding,
                )
                ServerSection.ARTISTS -> ArtistList(
                    artists = artists,
                    songs = loaded,
                    onOpenArtist = onOpenArtist,
                    contentPadding = listPadding,
                )
                ServerSection.SONGS -> SongList(
                    songs = loaded,
                    onPlay = { index -> vm.play(loaded, index) },
                    onLongClick = { song -> pendingAddition = PlaylistAdditions.ofSong(song) },
                    contentPadding = listPadding,
                )
            }
        }
    }
}

/**
 * "All" plus one chip per account. Horizontally scrollable rather than wrapping: account names
 * are user-typed and unbounded, and a wrapped chip row would push the library down by a variable
 * amount depending on what someone named their server.
 */
@Composable
private fun AccountChips(
    accounts: List<ServerAccount>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SIDE_MARGIN),
        horizontalArrangement = Arrangement.spacedBy(HEADER_GAP),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All") },
        )
        accounts.forEach { account ->
            FilterChip(
                selected = selected == account.sourceId,
                onClick = { onSelect(account.sourceId) },
                label = { Text(account.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/**
 * The server-filtered library is empty. Syncing and empty are different states for the same
 * reason scanning and empty are on the Songs tab (spec Task B §8): a just-added account has
 * nothing yet because its first sync is in flight, and "Nothing from Home yet" plus a button
 * would be a lie for the whole length of that sync. Offline needs no state of its own — the
 * cached catalogue stays in the table and shows as it always has.
 */
@Composable
private fun ServerEmpty(
    name: String,
    syncing: Boolean,
    onSync: () -> Unit,
    contentPadding: PaddingValues,
) {
    val palette = neutralPalette()
    if (syncing) {
        ScanningState(
            palette = palette,
            contentPadding = contentPadding,
            title = "Syncing with $name…",
            body = "Veldt is fetching the catalogue. Albums appear as soon as it finishes.",
        )
    } else {
        EmptyState(
            palette = palette,
            title = "Nothing from $name yet",
            body = "No music has arrived from this server. If it should have, check the " +
                "server under Settings › Servers, or sync again.",
            actionLabel = "Sync now",
            onAction = onSync,
            contentPadding = contentPadding,
        )
    }
}
