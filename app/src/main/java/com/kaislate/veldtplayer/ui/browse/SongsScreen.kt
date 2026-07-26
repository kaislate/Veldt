package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.components.ArtPlaceholder
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.components.paletteWash
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** Breathing room above the first row and below the last, on top of the window insets. */
private val LIST_AIR = 8.dp

/** The art-sized emblem every browse message surface is built around. */
private val EMBLEM_SIZE = 120.dp

/** Strength of the palette veil behind a message surface. See [paletteWash]. */
private const val VEIL_ALPHA = 0.14f

@Composable
fun SongsScreen(
    vm: BrowseViewModel,
    audioGranted: Boolean,
    onRequestAudio: () -> Unit,
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

    if (!audioGranted) {
        EmptyState(
            palette = palette,
            title = "Veldt needs access to your music",
            body = "Grant audio access and Veldt will index everything on this device.",
            actionLabel = "Grant access",
            onAction = onRequestAudio,
            contentPadding = contentPadding,
            modifier = modifier,
        )
        return
    }

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
    // The top one stays a padding modifier. Nothing is drawn over the status bar to
    // scrim it — no app bar, no scrim — so letting rows pass under it just collides
    // title text with the clock. Depth is worth having only where something is there to
    // provide it.
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
            )
        }
    }
}

/** Shown while the first scan is still filling an empty library. */
@Composable
fun ScanningState(
    palette: DominantColors,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BrowseMessage(
        palette = palette,
        title = "Scanning your library…",
        body = "Veldt is indexing the music on this device. Tracks appear as they're found.",
        contentPadding = contentPadding,
        modifier = modifier,
        emblem = {
            // No percentage and no button: the scan reports no progress and KEEP would
            // make a second request a no-op. An honest indeterminate spinner beats a
            // fake bar and a dead button.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(paletteWash(palette)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = palette.onBg.copy(alpha = 0.75f)) }
        },
    )
}

/**
 * Shared empty/permission surface — never a blank screen (spec §13), and never bare
 * centred Material text either: it is built on the same palette wash and the same
 * art-shaped emblem as the rows behind it.
 */
@Composable
fun EmptyState(
    palette: DominantColors,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    BrowseMessage(
        palette = palette,
        title = title,
        body = body,
        contentPadding = contentPadding,
        modifier = modifier,
        // The emblem IS an oversized album-art placeholder, so an empty library looks
        // like the same product as a full one rather than like an error page.
        emblem = { ArtPlaceholder(initial = '♪', palette = palette, modifier = Modifier.fillMaxSize()) },
        footer = { Button(onClick = onAction) { Text(actionLabel) } },
    )
}

/** The one full-screen message layout, so every such surface reads identically. */
@Composable
private fun BrowseMessage(
    palette: DominantColors,
    title: String,
    body: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    emblem: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // The veil goes UNDER the insets so the tint reaches the screen edges rather
            // than stopping in a visible rectangle at the status bar.
            .background(paletteWash(palette, alpha = VEIL_ALPHA))
            .padding(contentPadding)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The slot only sizes and clips; each emblem paints its own wash, so the empty
        // state can hand this straight to ArtPlaceholder instead of restating its glyph.
        Box(
            modifier = Modifier
                .size(EMBLEM_SIZE)
                .clip(RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) { emblem() }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        footer()
    }
}
