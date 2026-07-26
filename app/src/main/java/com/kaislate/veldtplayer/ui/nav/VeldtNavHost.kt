package com.kaislate.veldtplayer.ui.nav

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaislate.veldtplayer.ui.browse.BrowseViewModel
import com.kaislate.veldtplayer.ui.browse.SongsScreen

/**
 * SharedTransitionLayout wraps the NavHost so album art can morph continuously between
 * destinations (spec §7). Wrapping here — at the root, before any screen exists — is
 * why later screens can opt into the morph without restructuring navigation.
 *
 * Verified on device in Task 8: the API compiles under Compose BOM 2025.07.00, the
 * `AnimatedVisibilityScope` a shared element needs is the `composable { }` receiver
 * itself (`this@composable`), and the element genuinely interpolates its bounds rather
 * than cross-fading. No fallback is needed.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun VeldtNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items = rememberNavItems()
    val snackbarHostState = remember { SnackbarHostState() }

    PermissionGate(onGranted = { }) { audioGranted, requestAudio ->
        val vm: BrowseViewModel = hiltViewModel()

        LaunchedEffect(Unit) {
            vm.errors.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // Populate the library on open when access is already granted. WorkManager's
        // KEEP policy dedupes concurrent scans.
        LaunchedEffect(audioGranted) { if (audioGranted) vm.scan() }

        VeldtScaffold(
            currentRoute = currentRoute,
            items = items,
            snackbarHostState = snackbarHostState,
            onSelect = { route ->
                if (route != currentRoute) {
                    navController.navigate(route) {
                        popUpTo(Destinations.SONGS) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            },
        ) { padding ->
            SharedTransitionLayout {
                // The scaffold's insets are PASSED DOWN rather than applied here. A
                // Modifier.padding at this level would clip every screen above the
                // navigation bar; handing each screen its own insets lets a list scroll
                // beneath the translucent bar instead.
                NavHost(
                    navController = navController,
                    startDestination = Destinations.SONGS,
                ) {
                    composable(Destinations.SONGS) {
                        SongsScreen(
                            vm = vm,
                            audioGranted = audioGranted,
                            onRequestAudio = requestAudio,
                            contentPadding = padding,
                        )
                    }
                }
            }
        }
    }
}
