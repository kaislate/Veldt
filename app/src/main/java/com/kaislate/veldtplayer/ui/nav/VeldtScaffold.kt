package com.kaislate.veldtplayer.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector

/** A bottom-bar destination. [enabled] is false for slots not yet implemented. */
data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean,
)

/**
 * The Playlists slot is rendered DISABLED rather than omitted so that adding it in P1.4
 * changes a flag, not the bar's proportions — the layout never shifts under the user.
 */
@Composable
fun rememberNavItems(): List<NavItem> = remember {
    listOf(
        NavItem(Destinations.SONGS, "Songs", Icons.Filled.MusicNote, enabled = true),
        NavItem(Destinations.ALBUMS, "Albums", Icons.Filled.Album, enabled = false),
        NavItem(Destinations.ARTISTS, "Artists", Icons.Filled.Person, enabled = false),
        NavItem(Destinations.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic, enabled = false),
    )
}

@Composable
fun VeldtScaffold(
    currentRoute: String?,
    items: List<NavItem>,
    snackbarHostState: SnackbarHostState,
    onSelect: (String) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                items.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route,
                        enabled = item.enabled,
                        onClick = { onSelect(item.route) },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        content = content,
    )
}
