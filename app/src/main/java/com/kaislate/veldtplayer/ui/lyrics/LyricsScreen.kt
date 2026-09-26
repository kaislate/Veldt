// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.lyrics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.components.ArtBackdrop
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.nowplaying.NowPlayingViewModel
import com.kaislate.veldtplayer.ui.theme.LocalIsLightTheme
import com.kaislate.veldtplayer.ui.theme.backdropText
import com.kaislate.veldtplayer.ui.theme.rememberAnimatedPalette

/**
 * The full-screen lyrics route (spec §7): the same drifting [ArtBackdrop] as now-playing, and
 * [LyricsContent] filling it.
 *
 * Shares the activity-scoped [NowPlayingViewModel] the nav host already holds, so it shows the
 * same track, the same palette and the same lyrics state as the pane it was opened from, and
 * registers itself as a lyrics viewer for as long as it is composed ([LyricsVisibleWhile]) —
 * which is what keeps resolution running here even though the now-playing pane below it on the
 * back stack has left composition.
 *
 * Text tones are solved exactly as now-playing solves them — against the ANIMATED `palette.bg`
 * and `scrimAtText` — because the ground is the same backdrop; see NowPlayingScreen.
 */
@Composable
fun LyricsScreen(
    vm: NowPlayingViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.nowPlaying.collectAsStateWithLifecycle()
    val position by vm.positionMs.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val targetSeed by vm.seed.collectAsStateWithLifecycle()
    val isLight = LocalIsLightTheme.current
    val palette = rememberAnimatedPalette(targetSeed.colors(isLight = isLight))
    // Every glyph on this route sits on the lyrics floor (see below), so every glyph is solved at
    // the composited alpha that floor guarantees — lyricsTargetScrim, not the title band's.
    val text = targetSeed.backdropText(palette.bg, lyricsTargetScrim(isLight), isLight)
    val reduced = rememberReducedMotion()
    // Everything on this route — header and lyrics — sits far above the title band
    // scrimAtText describes, down to the very top of the frame where the backdrop's scrim is
    // weakest. So the whole content area is one lyrics region with a scrim FLOOR behind it,
    // lifting the weakest scrim anywhere on it to lyricsTargetScrim; [text], solved there, then
    // holds for every glyph on the screen. See lyricsScrimFloor.
    val ground = rememberLyricsGround()

    LyricsVisibleWhile(vm, visible = true)

    Box(
        modifier
            .fillMaxSize()
            .lyricsBackdrop(ground),
    ) {
        ArtBackdrop(
            art = state.art,
            palette = palette,
            reducedMotion = reduced,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .fillMaxSize()
                // Before the insets, so the floor also covers the status-bar strip.
                .lyricsRegion(ground)
                .lyricsScrimFloor(ground, palette.bg, isLight)
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = text.primary,
                    )
                }
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = text.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 56.dp),
                )
            }
            // A restored back stack can land here with the queue empty, exactly as it can on
            // now-playing; the state holder then stays Hidden (no song, nothing to resolve),
            // which LyricsContent would draw as a spinner that never finishes.
            if (!state.isActive) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing playing",
                        style = MaterialTheme.typography.headlineSmall,
                        color = text.primary,
                    )
                }
            } else {
                LyricsContent(
                    state = lyrics,
                    positionMs = position,
                    text = text,
                    onSeek = vm::seekTo,
                    onOpenSettings = onOpenSettings,
                    lineStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                )
            }
        }
    }
}
