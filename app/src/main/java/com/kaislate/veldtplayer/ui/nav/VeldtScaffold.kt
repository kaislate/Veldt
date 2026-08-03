// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

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
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.kaislate.veldtplayer.ui.motion.Motion
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
 */
@Composable
fun rememberNavItems(): List<NavItem> = remember {
    listOf(
        NavItem(Destinations.SONGS, "Songs", Icons.Filled.MusicNote, enabled = true),
        NavItem(Destinations.ALBUMS, "Albums", Icons.Filled.Album, enabled = true),
        NavItem(Destinations.ARTISTS, "Artists", Icons.Filled.Person, enabled = true),
        NavItem(Destinations.PLAYLISTS, "Playlists", Icons.AutoMirrored.Filled.QueueMusic, enabled = true),
    )
}

/**
 * [topBar] is a slot rather than a fixed bar because it is EMPTY on most destinations: the
 * detail screens and search draw their own headers with a back affordance in them, and a
 * second bar above those would be one row of chrome doing nothing. The caller decides per
 * route; an empty slot costs the Scaffold zero top padding, which is exactly what the
 * screens that pass content under the status bar already assume.
 *
 * [miniPlayer] is a slot for a different reason: it is one end of the track-art morph, and its
 * lifetime is therefore the CALLER's to decide, not this bar's. So it sits OUTSIDE the
 * navigation bar's [AnimatedVisibility] and manages its own visibility. Wrapping it was what
 * made the morph work going out and snap coming back: the chrome that was supposed to be an
 * end of the transition was being composed by that same transition. What the caller does with
 * the freedom — composed across the hand-over, absent once it settles — is in
 * `rememberMorphLinger`; this bar only guarantees it never overrides it.
 */
@Composable
fun VeldtScaffold(
    currentRoute: String?,
    items: List<NavItem>,
    snackbarHostState: SnackbarHostState,
    navigationBarVisible: Boolean,
    onSelect: (String) -> Unit,
    topBar: @Composable () -> Unit,
    miniPlayer: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = topBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Column {
                miniPlayer()
                // FADE ONLY — no expand/shrink, and that is load-bearing rather than taste.
                // Shrinking this bar would drag the mini-player above it downward for the
                // length of the very navigation that starts the morph, so the cover would
                // fly from a moving origin. Fading holds the height until the animation is
                // over, by which time the 420ms morph has long finished.
                AnimatedVisibility(
                    visible = navigationBarVisible,
                    enter = fadeIn(Motion.gentle),
                    exit = fadeOut(Motion.gentle),
                ) {
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
