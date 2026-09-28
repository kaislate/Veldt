// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
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
 * The bar item for tab [id], or null for an id this build does not know — and for the server tab
 * while [serverLabel] is null, since it exists only while a server account does. Shared by the
 * bar and Settings → Tabs, so a tab is named and drawn the same in both.
 *
 * [serverLabel] is the server type's name — "Navidrome", "Subsonic", "Servers" — per
 * `ServerTypeNames.tabLabel`.
 */
fun navItemFor(id: String, serverLabel: String?): NavItem? = when (id) {
    Destinations.SONGS -> NavItem(id, "Songs", Icons.Filled.MusicNote, enabled = true)
    Destinations.ALBUMS -> NavItem(id, "Albums", Icons.Filled.Album, enabled = true)
    Destinations.ARTISTS -> NavItem(id, "Artists", Icons.Filled.Person, enabled = true)
    Destinations.PLAYLISTS -> NavItem(id, "Playlists", Icons.AutoMirrored.Filled.QueueMusic, enabled = true)
    Destinations.FOLDERS -> NavItem(id, "Folders", Icons.Filled.Folder, enabled = true)
    // Dns (a server rack) over Cloud: at 24 dp the cloud reads as "online/backup", which is what
    // a streaming SERVICE is, not a server the user runs.
    Destinations.SERVER -> serverLabel?.let { NavItem(id, it, Icons.Filled.Dns, enabled = true) }
    else -> null
}

/**
 * The bar's items, in [shown] order (already through `TabArrangement.shown`: the user's order,
 * minus hidden tabs, minus the server tab while no server exists).
 *
 * **Up to six slots.** Five is Material 3's documented maximum, and it held as the ceiling until
 * the owner asked for server music to be its own tab (player-sheet/server-tab spec, Task B), then
 * for the tabs to be rearrangeable and hideable (Round 2 → D). The sixth slot exists only while a
 * server does, and hiding tabs only ever narrows the bar. At 360 dp six items get ~60 dp each,
 * which is why every label is single-line with an ellipsis (see [VeldtScaffold]) — "Navidrome"
 * must truncate, not wrap into a second line that re-lays the bar.
 */
@Composable
fun rememberNavItems(shown: List<String>, serverLabel: String?): List<NavItem> =
    remember(shown, serverLabel) { shown.mapNotNull { navItemFor(it, serverLabel) } }

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
 * [onReorder] receives the bar's new left-to-right route order after a long-press drag along it
 * (see [ReorderableNavigationBar]); the caller writes it back around whatever tabs the bar is not
 * showing.
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
    onReorder: (List<String>) -> Unit,
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
                ReorderableNavigationBar(
                    currentRoute = currentRoute,
                    items = items,
                    onSelect = onSelect,
                    onReorder = onReorder,
                )
            }
        },
        content = content,
    )
}

/** How much a tab grows while it is held — enough to read as lifted off the bar. */
private const val HELD_SCALE = 1.12f

/**
 * The navigation bar, with its tabs rearrangeable in place (player-sheet/server-tab spec,
 * Round 2 → D §2): long-press a tab to pick it up (with a haptic), drag it along the bar, and the
 * others make way as it passes their centres; releasing drops it and [onReorder] saves the order.
 * A plain tap still navigates.
 *
 * **The gesture lives on the bar, not on each item**, because an item cannot follow a finger into
 * its neighbour's slot while its neighbour owns that slot's pointer input. The bar maps the press
 * to a tab by the items' measured centres, snapshotted at pick-up (see [TabArrangement
 * .nearestSlot]); the slots never move during a drag — only which tab sits in each — so the
 * snapshot stays true while the order changes under it.
 *
 * **Coexisting with the items' own clicks.** Children see each pointer event before the bar, so
 * the item under a long-press still gets the release as a tap. That tap is dropped while a tab is
 * held (`held` is set at pick-up, long before the release, and cleared only in the drag's end
 * callback, which runs after the item's). Movement after pick-up is consumed by the bar, which
 * cancels the item's tap on its own.
 *
 * TalkBack users reorder in Settings → Tabs, which offers move up/down actions; a long-press drag
 * has no accessible equivalent on a bar.
 */
@Composable
private fun ReorderableNavigationBar(
    currentRoute: String?,
    items: List<NavItem>,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val currentItems by rememberUpdatedState(items)
    val currentOnReorder by rememberUpdatedState(onReorder)
    // Root-space bounds per route, refreshed while nothing is held.
    val bounds = remember { mutableMapOf<String, Rect>() }
    var barOriginX by remember { mutableFloatStateOf(0f) }
    // Non-null while a tab is held: the live order, the held route, the slot centres at pick-up,
    // and where in the tab the finger grabbed it (so the tab does not jump to centre on the finger).
    var liveOrder by remember { mutableStateOf<List<String>?>(null) }
    var held by remember { mutableStateOf<String?>(null) }
    var slotCenters by remember { mutableStateOf(emptyList<Float>()) }
    var grab by remember { mutableFloatStateOf(0f) }
    var fingerX by remember { mutableFloatStateOf(0f) }
    // Each tab's own interaction source, and the press it last started that has not yet ended.
    // Owned here, not left to NavigationBarItem, so a drag's end can finish a press the item never
    // will — see release().
    val sources = remember { mutableMapOf<String, MutableInteractionSource>() }
    val openPresses = remember { mutableMapOf<String, PressInteraction.Press>() }

    /**
     * Ends a pick-up. **Also cancels any press still open on a tab** — measured on the S21 FE
     * (fix round 1): after a long-press drag, the dropped tab kept its grey pressed highlight until
     * another tab was tapped. The item's click handling began a Press at touch-down, and the bar's
     * gesture then owned the pointer from pick-up to release, so the item never saw a release or
     * cancel of its own and never ended it. Emitting the Cancel ourselves clears the indication
     * the way an ordinary cancelled tap does. Only after an actual pick-up ([held] set): a plain
     * tap never reaches here with anything held, so its ripple is left entirely alone.
     */
    fun release() {
        if (held != null) {
            openPresses.forEach { (route, press) -> sources[route]?.tryEmit(PressInteraction.Cancel(press)) }
            openPresses.clear()
        }
        liveOrder = null
        held = null
    }

    val byRoute = items.associateBy { it.route }
    val order = liveOrder ?: items.map { it.route }

    // Translucent, because screens hand their window insets to a scrollable's contentPadding
    // rather than clipping themselves above the bar. Content passing beneath the tint is what
    // gives the bar somewhere to sit.
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = CHROME_ALPHA),
        modifier = Modifier
            .onGloballyPositioned { barOriginX = it.positionInRoot().x }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        val routes = currentItems.map { it.route }
                        val centers = routes.map { bounds[it]?.center?.x }
                        // One tab has nowhere to go; a missing measurement means a frame has not
                        // laid out yet. Either way nothing is picked up.
                        if (routes.size >= 2 && centers.none { it == null }) {
                            val x = barOriginX + offset.x
                            slotCenters = centers.filterNotNull()
                            val index = TabArrangement.nearestSlot(slotCenters, x)
                            held = routes[index]
                            liveOrder = routes
                            grab = x - slotCenters[index]
                            fingerX = x
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, delta ->
                        val route = held
                        val live = liveOrder
                        if (route != null && live != null) {
                            change.consume()
                            fingerX += delta.x
                            val target = TabArrangement.nearestSlot(slotCenters, fingerX - grab)
                            val from = live.indexOf(route)
                            if (target >= 0 && target != from) {
                                liveOrder = TabArrangement.moved(live, from, target)
                            }
                        }
                    },
                    onDragEnd = {
                        val live = liveOrder
                        if (live != null && live != currentItems.map { it.route }) currentOnReorder(live)
                        release()
                    },
                    onDragCancel = { release() },
                )
            },
    ) {
        order.forEach { route ->
            val item = byRoute[route] ?: return@forEach
            // Keyed, so each tab's ripple and selection state travel with it as slots change hands.
            key(route) {
                val source = remember { MutableInteractionSource() }
                DisposableEffect(source) {
                    sources[route] = source
                    onDispose {
                        sources.remove(route)
                        openPresses.remove(route)
                    }
                }
                // Tracks the one open press per tab that release() may have to cancel.
                LaunchedEffect(source) {
                    source.interactions.collect { interaction ->
                        when (interaction) {
                            is PressInteraction.Press -> openPresses[route] = interaction
                            is PressInteraction.Release ->
                                if (openPresses[route] == interaction.press) openPresses.remove(route)
                            is PressInteraction.Cancel ->
                                if (openPresses[route] == interaction.press) openPresses.remove(route)
                        }
                    }
                }
                NavigationBarItem(
                    interactionSource = source,
                    selected = currentRoute == item.route,
                    enabled = item.enabled,
                    // Dropped while a tab is held — see the KDoc.
                    onClick = { if (held == null) onSelect(item.route) },
                    // null, not the label: the visible Text below is already the item's
                    // accessible name, and naming the icon too makes TalkBack announce
                    // "Songs, Songs".
                    icon = { Icon(item.icon, contentDescription = null) },
                    // Single line, ellipsised: the server slot's label is a server's name and a
                    // sixth item narrows every slot. See rememberNavItems.
                    label = { Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier
                        .onGloballyPositioned { if (held == null) bounds[route] = it.boundsInRoot() }
                        .then(
                            if (route == held) {
                                Modifier
                                    .zIndex(1f)
                                    .graphicsLayer {
                                        // Read here, in the layer, so following the finger costs
                                        // no recomposition — only the order changing does.
                                        val slot = liveOrder?.indexOf(route) ?: -1
                                        translationX = if (slot in slotCenters.indices) {
                                            fingerX - grab - slotCenters[slot]
                                        } else {
                                            0f
                                        }
                                        scaleX = HELD_SCALE
                                        scaleY = HELD_SCALE
                                    }
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}
