package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.ui.components.ArtPlaceholder
import com.kaislate.veldtplayer.ui.components.paletteWash
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/**
 * The states a browse surface can be in other than "here is your music", plus the copy
 * they share — in one file, because five screens now use them. They lived in
 * `SongsScreen.kt` while the Songs tab was their only caller; once the permission surface
 * moved out to the nav host — so ONE gate covers all four destinations instead of each
 * screen carrying a copy — leaving the vocabulary inside a screen that no longer uses half
 * of it stopped making sense.
 */

/** "1 album", "4 albums" — a library full of "1 albums" reads as unfinished software. */
internal fun countOf(count: Int, noun: String): String =
    if (count == 1) "$count $noun" else "$count ${noun}s"

/**
 * Breathing room above the first row and below the last, on top of the window insets.
 * Shared by every browse surface so the tabs start at the same height.
 */
internal val LIST_AIR = 8.dp

/**
 * The side margin a browse surface's content starts at — the same 16dp [SongRow] and the
 * artist portrait row already inset themselves by, so labels, rows and shelves all line up
 * on one edge.
 */
internal val SIDE_MARGIN = 16.dp

/**
 * Quiet divider-by-typography. A scroll that holds two or three kinds of thing (an artist's
 * records then their tracks; search's artists, albums and songs) needs those runs NAMED,
 * not separated by rule lines — a hairline across an art-forward list is one more thing
 * competing with the artwork.
 */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = SIDE_MARGIN, top = 20.dp, bottom = 10.dp),
    )
}

/** The art-sized emblem every browse message surface is built around. */
private val EMBLEM_SIZE = 120.dp

/** Strength of the palette veil behind a message surface. See [paletteWash]. */
private const val VEIL_ALPHA = 0.14f

/**
 * The one audio-permission surface for the whole app.
 *
 * Rendered by `VeldtNavHost` in place of whichever destination was asked for, so a denied
 * user tapping Albums is told what is wrong instead of being shown an empty grid and a
 * Scan button that cannot possibly help.
 */
@Composable
fun AudioAccessRequired(
    onRequestAudio: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        palette = ColorExtractor.extract(null),
        title = "Veldt needs access to your music",
        body = "Grant audio access and Veldt will index everything on this device.",
        actionLabel = "Grant access",
        onAction = onRequestAudio,
        contentPadding = contentPadding,
        modifier = modifier,
    )
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

/**
 * The one full-screen message layout, so every such surface reads identically.
 *
 * Internal rather than private because not every message carries an action: search's
 * "start typing" and "no matches" surfaces have nothing to offer a button, and giving them
 * a dead one purely to reach [EmptyState] would be worse than reaching this directly.
 */
@Composable
internal fun BrowseMessage(
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
