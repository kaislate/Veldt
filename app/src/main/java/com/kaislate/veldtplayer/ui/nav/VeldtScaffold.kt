// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.kaislate.veldtplayer.ui.theme.CHROME_ALPHA

/** A bottom-bar destination. [enabled] is false for slots not yet implemented. */
data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
)

/**
 * The Playlists slot was rendered DISABLED through P1.3 so that turning it on in P1.4 changed a
 * flag, not the bar's proportions — the layout never shifted under the user. Task 6 flipped it.
 *
 * **Five fixed slots, and a sixth that comes and goes.** Five is Material 3's documented maximum,
 * and it held as the ceiling until the owner asked for server music to be its own tab (player-
 * sheet/server-tab spec, Task B — an explicit owner decision over a Settings-configurable tab
 * set). So the sixth slot exists only while [serverLabel] is non-null, i.e. while at least one
 * server account does: a device-only user keeps the five-item bar exactly as it was. At 360 dp six
 * items get ~60 dp each, which is why every label is single-line with an ellipsis (see
 * [VeldtScaffold]) — "Navidrome" must truncate, not wrap into a second line that re-lays the bar.
 * Any FURTHER dimension still needs a configurable tab set, not a seventh slot.
 *
 * [serverLabel] is the server type's name — "Navidrome", "Subsonic", "Servers" — per
 * `ServerTypeNames.tabLabel`. It is the one input, so the list is rebuilt only when the label
 * itself changes.
 */
@Composable
fun rememberNavItems(serverLabel: String?): List<NavItem> = remember(serverLabel) {
    listOfNotNull(
        NavItem(Destinations.SONGS, "Songs", Icons.Filled.MusicNote, enabled = true),
        NavItem(Destinations.ALBUMS, "Albums", Icons.Filled.Album, enabled = true),
        NavItem(Destinations.ARTISTS, "Artists", Icons.Filled.Person, enabled = true),
        NavItem(Destinations.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic, enabled = true),
        NavItem(Destinations.FOLDERS, "Folders", Icons.Filled.Folder, enabled = true),
        // Dns (a server rack) over Cloud: at 24 dp the cloud reads as "online/backup", which is
        // what a streaming SERVICE is, not a server the user runs. Last, so the five fixed slots
        // never move when it appears.
        serverLabel?.let { NavItem(Destinations.SERVER, it, Icons.Filled.Dns, enabled = true) },
    )
}

/**
 * [topBar] is a slot rather than a fixed bar because it is EMPTY on most destinations: the
 * detail screens and search draw their own headers with a back affordance in them, and a
 * second bar above those would be one row of chrome doing nothing. The caller decides per
 * route; an empty slot costs the Scaffold zero top padding, which is exactly what the
 * screens that pass content under the status bar already assume.
 *
 * [miniPlayer] is a slot for a different reason: it is where the now-playing sheet rests when
 * collapsed, and the caller wires it to that sheet (its fade, its drag, where the cover lands —
 * see `ui/nowplaying/PlayerSheet.kt`). This bar never decides anything about it. The navigation
 * bar below it is always shown: the expanded sheet simply covers it.
 *
 * [modifier] is for the caller's decisions about the whole scaffold — today, taking it out of the
 * accessibility tree while the now-playing sheet covers it.
 */
@Composable
fun VeldtScaffold(
    currentRoute: String?,
    items: List<NavItem>,
    snackbarHostState: SnackbarHostState,
    onSelect: (String) -> Unit,
    topBar: @Composable () -> Unit,
    miniPlayer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                miniPlayer()
                // Translucent, because screens hand their window insets to a scrollable's
                // contentPadding rather than clipping themselves above the bar. Content
                // passing beneath the tint is what gives the bar somewhere to sit.
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                        .copy(alpha = CHROME_ALPHA)
                ) {
                    items.forEach { item ->
                        NavigationBarItem(
                            selected = currentRoute == item.route,
                            enabled = item.enabled,
                            onClick = { onSelect(item.route) },
                            // null, not the label: the visible Text below is already the
                            // item's accessible name, and naming the icon too makes
                            // TalkBack announce "Songs, Songs".
                            icon = { Icon(item.icon, contentDescription = null) },
                            // Single line, ellipsised: the server slot's label is a server's
                            // name and a sixth item narrows every slot. See rememberNavItems.
                            label = {
                                Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }
            }
        },
        content = content,
    )
}
