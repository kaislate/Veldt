// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.kaislate.veldtplayer.ui.browse.FolderScreen
import com.kaislate.veldtplayer.ui.browse.FolderViewModel
import com.kaislate.veldtplayer.ui.browse.PlaylistDetailScreen
import com.kaislate.veldtplayer.ui.browse.PlaylistViewModel
import com.kaislate.veldtplayer.ui.browse.PlaylistsScreen
import com.kaislate.veldtplayer.ui.browse.SearchScreen
import com.kaislate.veldtplayer.ui.browse.ServerScreen
import com.kaislate.veldtplayer.ui.browse.ServerViewModel
import com.kaislate.veldtplayer.ui.browse.SongsScreen
import com.kaislate.veldtplayer.ui.components.MiniPlayer
import com.kaislate.veldtplayer.ui.motion.LocalNavAnimatedVisibilityScope
import com.kaislate.veldtplayer.ui.motion.LocalSharedTransitionScope
import com.kaislate.veldtplayer.ui.nowplaying.NowPlayingViewModel
import com.kaislate.veldtplayer.ui.nowplaying.PlayerSheet
import com.kaislate.veldtplayer.ui.nowplaying.miniPlayerOfSheet
import com.kaislate.veldtplayer.ui.nowplaying.miniPlayerThumbOfSheet
import com.kaislate.veldtplayer.ui.nowplaying.playerSheetHost
import com.kaislate.veldtplayer.ui.nowplaying.rememberPlayerSheetState
import com.kaislate.veldtplayer.ui.settings.NoticesScreen
import com.kaislate.veldtplayer.ui.settings.PillAppearanceScreen
import com.kaislate.veldtplayer.ui.settings.SettingsScreen
import com.kaislate.veldtplayer.ui.settings.TabsSettingsScreen
import com.kaislate.veldtplayer.ui.settings.accounts.AccountsScreen
import com.kaislate.veldtplayer.ui.theme.LocalIsLightTheme
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long an "open the player" request — the pill's deep link, or a play started from a list —
 * waits for the queue to read as active before giving up and leaving the app where it is.
 */
private const val OPEN_NOW_PLAYING_WAIT_MS = 3_000L

/**
 * The destinations that carry the app bar — the tabs, and only the tabs. Every other
 * destination draws its own header with a back affordance in it.
 */
private val TAB_ROUTES = setOf(
    Destinations.SONGS,
    Destinations.ALBUMS,
    Destinations.ARTISTS,
    Destinations.PLAYLISTS,
    Destinations.FOLDERS,
    Destinations.SERVER,
)

/**
 * SharedTransitionLayout wraps the WHOLE SCAFFOLD so album art can morph continuously
 * between destinations (spec §7).
 *
 * Verified on device in Task 8: the API compiles under Compose BOM 2025.07.00, the
 * `AnimatedVisibilityScope` a shared element needs is the `composable { }` receiver
 * itself (`this@composable`), and the element genuinely interpolates its bounds rather
 * than cross-fading. No fallback is needed.
 *
 * **Now-playing is not a destination.** It is the [PlayerSheet], drawn over the whole scaffold
 * as the layout's second child (SharedTransitionLayout stacks its children like a Box). It used
 * to be a route, which made it a screen you could only be ON or OFF: nothing was composed behind
 * it, so it could not follow a finger down, be peeked behind, or be caught mid-dismiss. Over a
 * live scaffold it can. The track cover's hand-over to the mini-player is the sheet's own
 * fraction-driven flight rather than a shared element, so the sheet does not need this layout's
 * scope; it sits inside it only because this is the box that holds everything.
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VeldtNavHost(openNowPlayingRequest: Int = 0) {
    val navController = rememberNavController()
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    // Resolved out here, above the permission gate, because the bar it labels is drawn whether or
    // not audio access was granted — and resolved once, at this level, because the tab's screen
    // reads the same instance (see ServerViewModel's KDoc).
    val serverVm: ServerViewModel = hiltViewModel()
    // The bar's arrangement (Round 2 → D): order, hidden tabs, "Open on" — resolved here for the
    // same reason as serverVm, and null until settings and the accounts table have both answered.
    val tabsVm: TabsViewModel = hiltViewModel()
    val tabs by tabsVm.state.collectAsStateWithLifecycle()
    val items = rememberNavItems(tabs?.shown.orEmpty(), tabs?.serverLabel)
    val snackbarHostState = remember { SnackbarHostState() }

    // The NavHost's start destination: the "Open on" tab, fixed ONCE per navigation state — not
    // re-read when the setting changes, because moving a live graph's start re-roots a back stack
    // the user is standing in. A change applies from the next cold start. Saveable, so rotation
    // and process restore rebuild the same graph the saved back stack was made for.
    var startRoute by rememberSaveable { mutableStateOf<String?>(null) }
    // The tab at the bottom of the back stack — what tab switches pop to. It starts as
    // [startRoute] and moves only if that tab stops being shown (see the effect below); tracked
    // rather than read from the graph so that a re-rooted stack still pops to a tab that is in it.
    var rootTab by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(tabs) {
        val ready = tabs ?: return@LaunchedEffect
        if (startRoute == null) {
            startRoute = ready.start
            rootTab = ready.start
        }
    }
    // Expanded/collapsed survives rotation; see rememberPlayerSheetState.
    val sheet = rememberPlayerSheetState()
    val sheetScope = rememberCoroutineScope()

    // The tab on screen is no longer in the bar: it was hidden in Settings → Tabs, or it is the
    // server tab and the last server account was removed (Settings is reachable from every tab's
    // app bar, so both are ordinary paths, not edges). The screen must not outlive its tab, so
    // go to the start tab — "Open on", or the first shown tab if that is gone too. Only once
    // `tabs` has answered: a null state would bounce a restored back stack before the accounts
    // table had said whether a server exists.
    LaunchedEffect(currentRoute, tabs) {
        val ready = tabs ?: return@LaunchedEffect
        val root = rootTab ?: return@LaunchedEffect
        if (currentRoute in TAB_ROUTES && currentRoute !in ready.shown) {
            val target = ready.start
            navController.navigate(target) {
                // The root itself is the tab going away: pop it too, and the target becomes the
                // new root. Otherwise the root stays and the target sits above it, as for a tap.
                popUpTo(root) { inclusive = root == currentRoute }
                launchSingleTop = true
            }
            if (root == currentRoute) rootTab = target
        }
    }

    PermissionGate(onGranted = { }) { audioGranted, audioBlocked, requestAudio ->
        val vm: BrowseViewModel = hiltViewModel()
        // Resolved HERE, and resolved once for the mini-player and the player sheet both — two
        // instances would be two palette extractions, two position collectors, and two surfaces
        // free to disagree about the same track. (It mattered more while now-playing was a
        // destination: hiltViewModel() inside a composable { } is scoped to that back-stack
        // entry.)
        val npVm: NowPlayingViewModel = hiltViewModel()
        // Resolved HERE for the same reason: the tab and a playlist's page are two back-stack
        // entries, and a per-entry instance would give them separate import reports — so a report
        // raised on the tab would vanish the moment the user opened the playlist it describes.
        val plVm: PlaylistViewModel = hiltViewModel()
        // Resolved HERE for the third time, and the reason is the sharpest of the three: a folder
        // stack is four back-stack entries deep in ordinary use, so a per-entry instance would be
        // four view models each collecting `folderTree()` — and that flow re-derives per collector
        // rather than sharing (see MusicRepository.folderTree). One instance, one derivation.
        val fVm: FolderViewModel = hiltViewModel()

        LaunchedEffect(Unit) {
            vm.errors.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // Playlist confirmations — "Added 12 tracks to “Road Trip”", "Added 36 tracks to the
        // queue" — go to the SAME snackbar host as playback errors, collected here rather than in
        // each screen. The add-to-playlist sheet is reachable from four surfaces and the append
        // action from a fifth; four collectors would be four chances for one screen to swallow the
        // only feedback an append ever produces.
        LaunchedEffect(Unit) {
            plVm.messages.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // The folder tab's own confirmation — "Added 42 tracks to the queue". Same host, collected
        // here for the same reason: appending is the one folder verb with no visible result, so a
        // screen that swallowed this message would make the verb look broken. Its playlist adds go
        // through plVm above and are already covered by that collector.
        LaunchedEffect(Unit) {
            fVm.messages.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // The server tab's pull-to-refresh outcome — "You're offline…", "Couldn't reach
        // Navidrome." Same host, collected here for the same reason as the two above.
        LaunchedEffect(Unit) {
            serverVm.messages.collect { message -> snackbarHostState.showSnackbar(message) }
        }

        // The pill's "open Veldt at now-playing" (NowPlayingDeepLink via MainActivity) expands
        // the player sheet. Waits, briefly, for the queue to read as active: on a cold start the
        // controller connects asynchronously, and the sheet refuses to open over nothing (it
        // collapses itself whenever the queue is empty). If it never becomes active the app
        // simply opens where it is.
        LaunchedEffect(openNowPlayingRequest) {
            if (openNowPlayingRequest <= 0) return@LaunchedEffect
            val active = withTimeoutOrNull(OPEN_NOW_PLAYING_WAIT_MS) {
                npVm.nowPlaying.first { it.isActive }
            }
            if (active != null) sheet.expand()
        }

        // "Starting playback from a list opens the player" — the owner's "immediately go to the
        // playing screen (with a very fast animation)". One event per PlaybackConnection.playFrom,
        // which is the entry point every list tap and Play/Shuffle button goes through and
        // nothing else (not append, not the car, the widget or the notification — see
        // playerRequests). The same short wait as the deep link, because the event arrives before
        // the controller has published the new queue on a cold start; once something is
        // playing it is already active and this opens at once. `fast` is Motion.sheetLaunch.
        LaunchedEffect(Unit) {
            npVm.playerRequests.collect {
                val active = withTimeoutOrNull(OPEN_NOW_PLAYING_WAIT_MS) {
                    npVm.nowPlaying.first { it.isActive }
                }
                if (active != null) sheetScope.launch { sheet.expand(fast = true) }
            }
        }

        // Populate the library on open when access is already granted. WorkManager's
        // KEEP policy dedupes concurrent scans.
        LaunchedEffect(audioGranted) { if (audioGranted) vm.scan() }

        // The host of both layers — the scaffold and the player sheet over it — and so the
        // coordinate space the sheet measures the mini-player and its own cover in.
        SharedTransitionLayout(Modifier.playerSheetHost(sheet)) {
            // Published once, for every surface below — the destinations AND the bottom
            // chrome. See ui/motion/SharedArt.kt for why the scopes travel as
            // CompositionLocals rather than parameters.
            CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                VeldtScaffold(
                    currentRoute = currentRoute,
                    items = items,
                    snackbarHostState = snackbarHostState,
                    // Always shown: the expanded player sheet is drawn OVER the bar (and the
                    // mini-player), so there is nothing to hide it for any more.
                    miniPlayer = {
                        // Every read is INSIDE this slot on purpose. Read at nav-host level they
                        // would invalidate the whole scaffold — including the NavHost — on each
                        // track change and, for the palette drift, on every frame of it.
                        val npState by npVm.nowPlaying.collectAsStateWithLifecycle()
                        // The remaining two are collected only once something is playing. The
                        // position ticker is WhileSubscribed and polls for as long as anything
                        // is attached, so collecting it unconditionally would have it running
                        // for the app's whole foreground life against an empty queue.
                        if (npState.isActive) {
                            val npSeed by npVm.seed.collectAsStateWithLifecycle()
                            val npPalette = npSeed.colors(isLight = LocalIsLightTheme.current)
                            // Held as State and never unwrapped here: the 250ms position tick is
                            // read in the mini-player's DRAW phase. See MiniPlayer's `progress`.
                            val npPosition = npVm.positionMs.collectAsStateWithLifecycle()

                            MiniPlayer(
                                state = npState,
                                palette = rememberAnimatedPalette(npPalette),
                                progress = {
                                    val duration = npState.durationMs
                                    if (duration > 0L) npPosition.value.toFloat() / duration else 0f
                                },
                                onPrevious = npVm::previous,
                                onToggle = npVm::toggle,
                                onNext = npVm::next,
                                // Idempotent: a second tap while the sheet is opening just
                                // restarts the same settle towards the same end.
                                onOpen = { sheetScope.launch { sheet.expand() } },
                                // The sheet's half of the row: its fade, where the sheet rests,
                                // and dragging it up. See miniPlayerOfSheet.
                                modifier = Modifier.miniPlayerOfSheet(sheet),
                                thumbModifier = Modifier.miniPlayerThumbOfSheet(sheet),
                            )
                        }
                    },
                    onSelect = { route ->
                        if (route != currentRoute) {
                            navController.navigate(route) {
                                // The root tab, not Songs: with "Open on" the stack's bottom is
                                // whichever tab the app opened on. See rootTab.
                                popUpTo(rootTab ?: route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                    onReorder = tabsVm::reorderShown,
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
                                    IconButton(
                                        // launchSingleTop for the same reason as search above.
                                        onClick = {
                                            navController.navigate(Destinations.SETTINGS) {
                                                launchSingleTop = true
                                            }
                                        },
                                    ) {
                                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                    // While the player sheet covers the app, the app is not there for a screen
                    // reader either: it stays composed (that is what makes the peek possible),
                    // so without this TalkBack would walk straight off the player into the
                    // library rows hidden behind it.
                    modifier = if (sheet.isExpanded) {
                        Modifier.clearAndSetSemantics { }
                    } else {
                        Modifier
                    },
                ) { padding ->
                    // The scaffold's insets are PASSED DOWN rather than applied here. A
                    // Modifier.padding at this level would clip every screen above the
                    // navigation bar; handing each screen its own insets lets a list scroll
                    // beneath the translucent bar instead.
                    // Nothing until the start tab is known — a frame or two on a cold start, the
                    // time DataStore and the accounts table take to answer. Starting on Songs and
                    // hopping would flash the wrong tab and leave Songs under the real one.
                    val start = startRoute ?: return@VeldtScaffold
                    NavHost(
                        navController = navController,
                        startDestination = start,
                    ) {
                        veldtDestination(
                            Destinations.SONGS, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            SongsScreen(vm = vm, playlistVm = plVm, contentPadding = padding)
                        }
                        veldtDestination(
                            Destinations.ALBUMS, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            AlbumsScreen(
                                vm = vm,
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.ARTISTS, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            ArtistsScreen(
                                vm = vm,
                                onOpenArtist = { key ->
                                    navController.navigate(Destinations.artistDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.PLAYLISTS, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            PlaylistsScreen(
                                vm = plVm,
                                onOpenPlaylist = { id ->
                                    navController.navigate(Destinations.playlistDetail(id))
                                },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.FOLDERS, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            FolderScreen(
                                vm = fVm,
                                playlistVm = plVm,
                                folderKey = null,
                                onOpenFolder = { key ->
                                    navController.navigate(Destinations.folder(key))
                                },
                                navController = navController,
                                contentPadding = padding,
                            )
                        }
                        // Behind the audio gate like the other tabs, though it streams: it is library
                        // content, and one gate for "may library content be shown" is the rule.
                        veldtDestination(
                            Destinations.SERVER, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            ServerScreen(
                                vm = serverVm,
                                playlistVm = plVm,
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                onOpenArtist = { key ->
                                    navController.navigate(Destinations.artistDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        // One destination PER folder, so back pops one level — see FolderScreen.
                        //
                        // The argument is coerced to "" exactly as ALBUM_DETAIL coerces its own —
                        // spelled `?: ""` here and `.orEmpty()` below, which are the same thing.
                        // The fallback earns its place for a reason that does not apply there,
                        // though: on this screen `null` is not "no key", it is the TAB ROOT.
                        // Letting an absent argument through as null would open the root under a
                        // `folder/…` route. `""` is a key no tree can hold — every node's key
                        // starts with a volume name, which is never empty — so it resolves to
                        // nothing and falls to the same surface a deleted folder does: "Folder
                        // unavailable", or ScanningState while a scan is still in flight. Pinned in
                        // FolderViewModelTest, at the view model, since nothing in this source set
                        // can drive the nav host itself.
                        veldtDestination(
                            Destinations.FOLDER_DETAIL, audioGranted, audioBlocked, requestAudio, padding,
                        ) { entry ->
                            FolderScreen(
                                vm = fVm,
                                playlistVm = plVm,
                                folderKey = entry.arguments?.getString(Destinations.ARG_KEY) ?: "",
                                onOpenFolder = { key ->
                                    navController.navigate(Destinations.folder(key))
                                },
                                navController = navController,
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.PLAYLIST_DETAIL, audioGranted, audioBlocked, requestAudio, padding,
                        ) { entry ->
                            // The argument is declared as a string and parsed here rather than
                            // typed as a NavType.LongType, because a restored back stack can hand
                            // back anything at all and a malformed id must land on the screen's
                            // own "playlist deleted" surface, not throw out of the nav graph.
                            // -1 matches no row, which is exactly what a bad id should mean.
                            val id = entry.arguments?.getString(Destinations.ARG_ID)
                                ?.toLongOrNull() ?: -1L
                            PlaylistDetailScreen(
                                vm = plVm,
                                playlistId = id,
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.SEARCH, audioGranted, audioBlocked, requestAudio, padding,
                        ) {
                            SearchScreen(
                                vm = vm,
                                playlistVm = plVm,
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
                            Destinations.ALBUM_DETAIL, audioGranted, audioBlocked, requestAudio, padding,
                        ) { entry ->
                            AlbumDetailScreen(
                                vm = vm,
                                playlistVm = plVm,
                                albumKey = entry.arguments?.getString(Destinations.ARG_KEY).orEmpty(),
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        veldtDestination(
                            Destinations.ARTIST_DETAIL, audioGranted, audioBlocked, requestAudio, padding,
                        ) { entry ->
                            ArtistDetailScreen(
                                vm = vm,
                                playlistVm = plVm,
                                artistKey = entry.arguments?.getString(Destinations.ARG_KEY).orEmpty(),
                                onBack = { navController.popBackStack() },
                                onOpenAlbum = { key ->
                                    navController.navigate(Destinations.albumDetail(key))
                                },
                                contentPadding = padding,
                            )
                        }
                        // Plain `composable`, not `veldtDestination`: settings and the notices
                        // it links to are not library content, so they carry no audio gate.
                        composable(Destinations.SETTINGS) {
                            SettingsScreen(
                                onBack = { navController.popBackStack() },
                                onOpenNotices = {
                                    navController.navigate(Destinations.NOTICES) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenAccounts = {
                                    navController.navigate(Destinations.ACCOUNTS) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenPillAppearance = {
                                    navController.navigate(Destinations.PILL_APPEARANCE) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenTabs = {
                                    navController.navigate(Destinations.TABS) {
                                        launchSingleTop = true
                                    }
                                },
                                contentPadding = padding,
                            )
                        }
                        // A page of settings, like pill appearance. Shares the nav host's
                        // TabsViewModel, so the bar behind it and this list are one state.
                        composable(Destinations.TABS) {
                            TabsSettingsScreen(
                                vm = tabsVm,
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        // Plain `composable` for the same reason settings is one: it is a
                        // page of settings.
                        composable(Destinations.PILL_APPEARANCE) {
                            PillAppearanceScreen(
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        composable(Destinations.NOTICES) {
                            NoticesScreen(
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                        // Plain `composable` for the same reason settings is one: a server
                        // account is configuration, not library content, so it carries no
                        // audio gate — a user with audio access denied must still be able to
                        // reach the screen that adds a server.
                        composable(Destinations.ACCOUNTS) {
                            AccountsScreen(
                                onBack = { navController.popBackStack() },
                                contentPadding = padding,
                            )
                        }
                    }
                }
                // Over everything, the scaffold's bars included. Composes nothing while collapsed
                // at rest; see PlayerSheet.
                PlayerSheet(
                    sheet = sheet,
                    vm = npVm,
                    snackbarHostState = snackbarHostState,
                    onOpenSettings = {
                        navController.navigate(Destinations.SETTINGS) {
                            launchSingleTop = true
                        }
                    },
                )
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
    audioBlocked: Boolean,
    onRequestAudio: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable (NavBackStackEntry) -> Unit,
) = composable(route) { entry ->
    CompositionLocalProvider(LocalNavAnimatedVisibilityScope provides this@composable) {
        if (audioGranted) {
            content(entry)
        } else {
            AudioAccessRequired(
                onRequestAudio = onRequestAudio,
                blocked = audioBlocked,
                contentPadding = contentPadding,
            )
        }
    }
}
