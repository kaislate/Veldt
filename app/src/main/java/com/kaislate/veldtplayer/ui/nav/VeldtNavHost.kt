package com.kaislate.veldtplayer.ui.nav

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaislate.veldtplayer.ui.browse.AlbumDetailScreen
import com.kaislate.veldtplayer.ui.browse.AlbumsScreen
import com.kaislate.veldtplayer.ui.browse.ArtistDetailScreen
import com.kaislate.veldtplayer.ui.browse.ArtistsScreen
import com.kaislate.veldtplayer.ui.browse.AudioAccessRequired
import com.kaislate.veldtplayer.ui.browse.BrowseViewModel
import com.kaislate.veldtplayer.ui.browse.SearchScreen
import com.kaislate.veldtplayer.ui.browse.SongsScreen
import com.kaislate.veldtplayer.ui.components.MiniPlayer
import com.kaislate.veldtplayer.ui.motion.LocalNavAnimatedVisibilityScope
import com.kaislate.veldtplayer.ui.motion.LocalSharedTransitionScope
import com.kaislate.veldtplayer.ui.nowplaying.NowPlayingScreen
import com.kaislate.veldtplayer.ui.nowplaying.NowPlayingViewModel
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette

/**
 * The destinations that carry the app bar — the tabs, and only the tabs. Every other
 * destination draws its own header with a back affordance in it.
 */
private val TAB_ROUTES = setOf(Destinations.SONGS, Destinations.ALBUMS, Destinations.ARTISTS)

/**
 * SharedTransitionLayout wraps the WHOLE SCAFFOLD so album art can morph continuously
 * between destinations (spec §7) — and so that the mini-player can take part.
 *
 * It used to sit inside the Scaffold's content slot, which was enough while every end of
 * every morph was a nav destination. The mini-player is chrome: docked as `bottomBar`, it
 * was outside the transition scope entirely and its `sharedElement` would have silently
 * no-opped. Hoisting is the whole fix on the SharedTransitionScope side; the
 * AnimatedVisibilityScope side is handled in [VeldtScaffold], because a mini-player is not a
 * destination and has no `composable { }` receiver to borrow one from.
 *
 * Verified on device in Task 8: the API compiles under Compose BOM 2025.07.00, the
 * `AnimatedVisibilityScope` a shared element needs is the `composable { }` receiver
 * itself (`this@composable`), and the element genuinely interpolates its bounds rather
 * than cross-fading. No fallback is needed.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VeldtNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val items = rememberNavItems()
    val snackbarHostState = remember { SnackbarHostState() }

    PermissionGate(onGranted = { }) { audioGranted, requestAudio ->
        val vm: BrowseViewModel = hiltViewModel()
        // Resolved HERE, not inside the now-playing destination. hiltViewModel() inside a
        // composable { } is scoped to that back-stack entry, so the screen and the
        // mini-player would hold two instances — two palette extractions, two position
        // collectors, and two surfaces free to disagree about the same track.
        val npVm: NowPlayingViewModel = hiltViewModel()

        LaunchedEffect(Unit) {
            vm.errors.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // Populate the library on open when access is already granted. WorkManager's
        // KEEP policy dedupes concurrent scans.
        LaunchedEffect(audioGranted) { if (audioGranted) vm.scan() }

        // Now-playing is a full-bleed surface with its own backdrop: the tab bar and the
        // mini-player would sit on top of the artwork, and the mini-player would be a second
        // copy of what fills the screen behind it.
        val onNowPlaying = currentRoute == Destinations.NOW_PLAYING

        SharedTransitionLayout {
            // Published once, for every surface below — the destinations AND the bottom
            // chrome. See ui/motion/SharedArt.kt for why the scopes travel as
            // CompositionLocals rather than parameters.
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                VeldtScaffold(
                    currentRoute = currentRoute,
                    items = items,
                    snackbarHostState = snackbarHostState,
                    bottomChromeVisible = !onNowPlaying,
                    miniPlayer = {
                        // Every read is INSIDE this slot on purpose. Read at nav-host level they
                        // would invalidate the whole scaffold — including the NavHost — on each
                        // track change and, for the palette drift, on every frame of it.
                        val npState by npVm.nowPlaying.collectAsStateWithLifecycle()
                        val npPalette by npVm.palette.collectAsStateWithLifecycle()
                        // Held as State and never unwrapped here: the 250ms position tick is read
                        // in the mini-player's DRAW phase. See MiniPlayer's `progress` parameter.
                        val npPosition = npVm.positionMs.collectAsStateWithLifecycle()

                        MiniPlayer(
                            state = npState,
                            palette = rememberAnimatedPalette(npPalette),
                            progress = {
                                val duration = npState.durationMs
                                if (duration > 0L) npPosition.value.toFloat() / duration else 0f
                            },
                            onToggle = npVm::toggle,
                            onNext = npVm::next,
                            onOpen = {
                                // launchSingleTop: a second tap while the screen is opening must not
                                // stack a second copy for the user to dismiss twice.
                                navController.navigate(Destinations.NOW_PLAYING) {
                                    launchSingleTop = true
                                }
                            },
                        )
                    },
                    onSelect = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                popUpTo(Destinations.SONGS) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    topBar = {
                        if (currentRoute in TAB_ROUTES) {
                            TopAppBar(
                                // titleLarge is already the display face (see Type.kt), so the
                                // wordmark needs no styling of its own.
                                title = { Text("Veldt") },
                                actions = {
                                    IconButton(
                                        // launchSingleTop: a double tap must not stack two search
                                        // screens for the user to back out of twice.
                                        onClick = {
                                            navController.navigate(Destinations.SEARCH) {
                                                launchSingleTop = true
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.Search, contentDescription = "Search")
                                    }
                                },
                                // Transparent, because nothing scrolls under this bar — the screens
                                // are padded below it. An opaque container would only draw a seam
                                // between two surfaces of the same colour.
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                ),
                            )
                        }
                    },
                ) { padding ->
                    // The scaffold's insets are PASSED DOWN rather than applied here. A
                    // Modifier.padding at this level would clip every screen above the
                    // navigation bar; handing each screen its own insets lets a list scroll
                    // beneath the translucent bar instead.
                    NavHost(
                        navController = navController,
                        startDestination = Destinations.SONGS,
                    ) {
                        veldtDestination(Destinations.SONGS, audioGranted, requestAudio, padding) {
                            SongsScreen(vm = vm, contentPadding = padding)
                        }
                        veldtDestination(Destinations.ALBUMS, audioGranted, requestAudio, padding) {
                            AlbumsScreen(
                                vm = vm,
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(Destinations.ARTISTS, audioGranted, requestAudio, padding) {
                            ArtistsScreen(
                                vm = vm,
                                onOpenArtist = { key ->
                                    navController.navigate(Destinations.artistDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(Destinations.SEARCH, audioGranted, requestAudio, padding) {
                            SearchScreen(
                                vm = vm,
                                onBack = { navController.popBackStack() },
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                onOpenArtist = { key ->
                                    navController.navigate(Destinations.artistDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.ALBUM_DETAIL, audioGranted, requestAudio, padding,
                        ) { entry ->
                            AlbumDetailScreen(
                                vm = vm,
                                albumKey = entry.arguments?.getString(Destinations.ARG_KEY).orEmpty(),
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.ARTIST_DETAIL, audioGranted, requestAudio, padding,
                        ) { entry ->
                            ArtistDetailScreen(
                                vm = vm,
                                artistKey = entry.arguments?.getString(Destinations.ARG_KEY).orEmpty(),
                                onBack = { navController.popBackStack() },
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        // No contentPadding: this screen hides the bottom chrome and reads
                        // the window insets itself, so it never depends on a PaddingValues
                        // that is mid-animation. See NowPlayingScreen.
                        veldtDestination(
                            Destinations.NOW_PLAYING, audioGranted, requestAudio, padding,
                        ) {
                            NowPlayingScreen(
                                vm = npVm,
                                onCollapse = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One destination, with the two things EVERY destination needs done to it.
 *
 * **The audio gate lives here, not in the screens.** Only the Songs tab used to carry it,
 * so a user who denied access and tapped Albums got a "Scanning…" flash and then a Scan
 * button that WorkManager would no-op — the screen had no way to know why it was empty.
 * The alternative was a third and fourth copy of the same check inside the screens; a
 * destination is the right altitude for "may this content be shown at all".
 *
 * **The AnimatedVisibilityScope is published here** because it is the `composable { }`
 * receiver and exists nowhere else, and because a shared element needs the scope of the
 * destination it is IN — not the one it came from.
 */
private fun NavGraphBuilder.veldtDestination(
    route: String,
    audioGranted: Boolean,
    onRequestAudio: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route) { entry ->
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
        if (audioGranted) {
            content(entry)
        } else {
            AudioAccessRequired(onRequestAudio = onRequestAudio, contentPadding = contentPadding)
        }
    }
}
