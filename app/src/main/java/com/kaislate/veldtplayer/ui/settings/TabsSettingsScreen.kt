// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel
import com.kaislate.veldtplayer.ui.nav.TabArrangement
import com.kaislate.veldtplayer.ui.nav.TabsViewModel
import com.kaislate.veldtplayer.ui.nav.navItemFor

/**
 * Settings → Tabs (player-sheet/server-tab spec, Round 2 → D §1, §3): every tab that can exist
 * right now — the server tab only while a server does — with a drag handle to reorder and a
 * switch to hide, then "Open on", then "Reset to default".
 *
 * Takes the nav host's [TabsViewModel] rather than its own, so the bar under this page and this
 * list are literally one state: a switch flipped here removes the tab from the bar in the same
 * frame the setting lands.
 *
 * **The last shown tab's switch is disabled**, not merely ignored ([TabArrangement.canHide]); a
 * switch that snaps back on would read as broken. Reordering is also offered as "Move up" / "Move
 * down" accessibility actions on each row, since a drag handle is no use to a screen reader.
 */
@Composable
fun TabsSettingsScreen(
    vm: TabsViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val direction = LocalLayoutDirection.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            )
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        SettingsHeader(title = "Tabs", onBack = onBack)
        val tabs = state ?: return@Column

        Text(
            "Drag to reorder. You can also long-press a tab on the bar and slide it along.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
        )

        ReorderableTabList(
            available = tabs.available,
            serverLabel = tabs.serverLabel,
            isShown = { it in tabs.shown },
            canHide = tabs::canHide,
            onMove = vm::move,
            onShownChange = vm::setShown,
        )

        SectionLabel("Open on")
        RadioGroup(
            caption = "The tab Veldt opens to. If it is hidden or gone, Veldt opens on the " +
                "first tab shown.",
            options = tabs.shown.mapNotNull { id -> navItemFor(id, tabs.serverLabel)?.let { id to it.label } },
            selected = tabs.start,
            onSelect = vm::setStart,
        )

        TextButton(
            onClick = vm::reset,
            modifier = Modifier.padding(horizontal = SIDE_MARGIN - 12.dp, vertical = 8.dp),
        ) { Text("Reset to default") }
    }
}

/**
 * The rows, drag-to-reorder. At most six rows, so a plain Column rather than a lazy list: every
 * row is always composed, which is what lets a row follow the finger past its neighbours without
 * a lazy list's item-placement machinery.
 *
 * While a row is held, the list is SHOWN in the order it would drop in
 * ([TabArrangement.dragTarget]) and the held row is offset so it stays under the finger. On
 * release the move is written, and the dropped order is kept on screen ([dropped]) until the
 * stored order arrives, so the row does not flick back to its old slot for the frames the write
 * takes.
 */
@Composable
private fun ReorderableTabList(
    available: List<String>,
    serverLabel: String?,
    isShown: (String) -> Boolean,
    canHide: (String) -> Boolean,
    onMove: (from: Int, to: Int) -> Unit,
    onShownChange: (String, Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Read through these inside the gesture: its pointerInput is keyed on the row's id so the
    // drag survives the row moving, which means its lambda outlives the list it was created with.
    val currentAvailable by rememberUpdatedState(available)
    val currentOnMove by rememberUpdatedState(onMove)
    var rowHeight by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf<String?>(null) }
    var heldFrom by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dropped by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(available) { dropped = null }

    val target = if (held != null) {
        TabArrangement.dragTarget(heldFrom, offset, rowHeight.toFloat(), available.size)
    } else {
        -1
    }
    val order = when {
        held != null -> TabArrangement.moved(available, heldFrom, target)
        else -> dropped ?: available
    }

    Column {
        order.forEach { id ->
            val item = navItemFor(id, serverLabel) ?: return@forEach
            key(id) {
                val index = available.indexOf(id)
                val isHeld = id == held
                val shown = isShown(id)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { rowHeight = it.height }
                        .zIndex(if (isHeld) 1f else 0f)
                        .graphicsLayer {
                            if (isHeld) translationY = offset - (target - heldFrom) * rowHeight
                        }
                        .then(
                            if (isHeld) {
                                Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else {
                                Modifier
                            },
                        )
                        // Merged, so the row reads as one item — "Albums, switch, on" — carrying
                        // its move actions, rather than an unnamed switch beside a label.
                        .semantics(mergeDescendants = true) {
                            customActions = buildList {
                                if (index > 0) {
                                    add(CustomAccessibilityAction("Move up") { onMove(index, index - 1); true })
                                }
                                if (index < available.lastIndex) {
                                    add(CustomAccessibilityAction("Move down") { onMove(index, index + 1); true })
                                }
                            }
                        }
                        .padding(end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The handle alone starts a drag, so a vertical swipe elsewhere on the row
                    // still scrolls the page.
                    Icon(
                        Icons.Filled.DragHandle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(48.dp)
                            .padding(12.dp)
                            .pointerInput(id) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        held = id
                                        heldFrom = currentAvailable.indexOf(id)
                                        offset = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onVerticalDrag = { change, dy ->
                                        change.consume()
                                        offset += dy
                                    },
                                    onDragEnd = {
                                        val list = currentAvailable
                                        val to = TabArrangement.dragTarget(
                                            heldFrom, offset, rowHeight.toFloat(), list.size,
                                        )
                                        if (to != heldFrom) {
                                            dropped = TabArrangement.moved(list, heldFrom, to)
                                            currentOnMove(heldFrom, to)
                                        }
                                        held = null
                                    },
                                    onDragCancel = { held = null },
                                )
                            },
                    )
                    Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = shown,
                        onCheckedChange = { onShownChange(id, it) },
                        // Hiding the last shown tab is not offered at all — see the screen KDoc.
                        enabled = !shown || canHide(id),
                    )
                }
            }
        }
    }
}
