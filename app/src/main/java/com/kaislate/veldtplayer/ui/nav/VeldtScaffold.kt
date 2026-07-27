package com.kaislate.veldtplayer.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.kaislate.veldtplayer.ui.motion.LocalNavAnimatedVisibilityScope
import com.kaislate.veldtplayer.ui.motion.Motion

/**
 * Bottom-chrome opacity. High enough that the labels never fight the artwork scrolling
 * under them, low enough that something is visibly passing behind — at full opacity the
 * bar reads as a wall rather than as a layer.
 *
 * Shared with the mini-player, which sits directly on top of the navigation bar: two pieces
 * of chrome at different opacities read as two stacked slabs rather than one pane.
 */
internal const val CHROME_ALPHA = 0.94f

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
 *
 * [miniPlayer] is a slot for the same reason but with one extra requirement: it is a shared
 * element, so it needs an `AnimatedVisibilityScope`, and it is NOT a nav destination so it
 * has no `composable { }` receiver to borrow one from. The bottom chrome's own
 * `AnimatedVisibility` is that scope, and it is published here — see
 * [LocalNavAnimatedVisibilityScope].
 */
@Composable
fun VeldtScaffold(
    currentRoute: String?,
    items: List<NavItem>,
    snackbarHostState: SnackbarHostState,
    bottomChromeVisible: Boolean,
    onSelect: (String) -> Unit,
    topBar: @Composable () -> Unit,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // FADE ONLY — no expand/shrink, and that is load-bearing rather than taste.
            // The mini-player's thumbnail is the source of the art morph into the
            // now-playing screen, which is the same navigation that hides this chrome; a
            // size animation here would slide that source downward for the length of the
            // morph and the cover would fly from a moving start.
            AnimatedVisibility(
                visible = bottomChromeVisible,
                enter = fadeIn(Motion.gentle),
                exit = fadeOut(Motion.gentle),
            ) {
                Column {
                    CompositionLocalProvider(
                        LocalNavAnimatedVisibilityScope provides this@AnimatedVisibility,
                    ) {
                        miniPlayer()
                    }
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
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
        content = content,
    )
}
