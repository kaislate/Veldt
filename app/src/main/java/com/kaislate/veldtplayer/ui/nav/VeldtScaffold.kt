package com.kaislate.veldtplayer.ui.nav

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
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
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Bottom-bar opacity. High enough that the labels never fight the artwork scrolling
 * under them, low enough that something is visibly passing behind — at full opacity the
 * bar reads as a wall rather than as a layer.
 */
private const val BAR_ALPHA = 0.94f

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
        NavItem(Destinations.ALBUMS, "Albums", Icons.Filled.Album, enabled = true),
        NavItem(Destinations.ARTISTS, "Artists", Icons.Filled.Person, enabled = true),
        NavItem(Destinations.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic, enabled = false),
    )
}

/**
 * [topBar] is a slot rather than a fixed bar because it is EMPTY on most destinations: the
 * detail screens and search draw their own headers with a back affordance in them, and a
 * second bar above those would be one row of chrome doing nothing. The caller decides per
 * route; an empty slot costs the Scaffold zero top padding, which is exactly what the
 * screens that pass content under the status bar already assume.
 */
@Composable
fun VeldtScaffold(
    currentRoute: String?,
    items: List<NavItem>,
    snackbarHostState: SnackbarHostState,
    onSelect: (String) -> Unit,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Translucent, because screens hand their window insets to a scrollable's
            // contentPadding rather than clipping themselves above the bar. Content
            // passing beneath the tint is what gives the bar somewhere to sit.
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
                    .copy(alpha = BAR_ALPHA)
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
                        label = { Text(item.label) },
                    )
                }
            }
        },
        content = content,
    )
}
