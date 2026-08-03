// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor

@Composable
fun SongsScreen(
    vm: BrowseViewModel,
    playlistVm: PlaylistViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, not collectAsState: every VM flow here is
    // WhileSubscribed, and a backgrounded screen must let the upstream stop.
    val songs by vm.songs.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    // Browse rows use the neutral fallback palette; per-track colour is a now-playing
    // concern (a list themed by 300 different covers would be noise, not craft).
    val palette = ColorExtractor.extract(null)

    // Which track a long press is currently offering to file. Held above the list, so the sheet
    // survives its row scrolling away underneath it.
    var pendingAddition by remember { mutableStateOf<PlaylistAddition?>(null) }
    AddToPlaylistHost(
        vm = playlistVm,
        addition = pendingAddition,
        onDismiss = { pendingAddition = null },
    )

    // Audio access is NOT checked here. VeldtNavHost gates all four destinations, so this
    // screen is only ever composed with access granted — see AudioAccessRequired.

    if (songs.isEmpty()) {
        // THREE states here, not two. On a fresh install the scan is enqueued and Room
        // reports an empty library in the same breath, so collapsing these two would
        // greet every new user with "No songs yet" for the length of the first scan —
        // and offer them a Scan button that WorkManager's KEEP policy would no-op.
        if (scanning) {
            ScanningState(
                palette = palette,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        } else {
            EmptyState(
                palette = palette,
                title = "No songs yet",
                body = "Nothing turned up in the media index. Run a scan once your music " +
                    "is on the device.",
                actionLabel = "Scan library",
                onAction = vm::scan,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        return
    }

    // The two insets are treated DIFFERENTLY, on purpose.
    //
    // The bottom one becomes contentPadding, so rows scroll beneath the translucent
    // navigation bar and the bar reads as a layer over the library rather than a wall
    // beside it. Padding the container instead would buy the same air and none of the
    // depth.
    //
    // The top one stays a padding modifier. The tabs' app bar is transparent and holds
    // the wordmark, so nothing up there scrims anything — letting rows pass under it
    // would just collide title text with the wordmark and the clock. Depth is worth
    // having only where something is there to provide it.
    val direction = LocalLayoutDirection.current
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            ),
        contentPadding = PaddingValues(
            top = LIST_AIR,
            bottom = contentPadding.calculateBottomPadding() + LIST_AIR,
        ),
    ) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song = song,
                palette = palette,
                onClick = { vm.play(songs, index) },
                modifier = Modifier.staggeredEntrance(index, reduced),
                onLongClick = { pendingAddition = PlaylistAdditions.ofSong(song) },
            )
        }
    }
}
