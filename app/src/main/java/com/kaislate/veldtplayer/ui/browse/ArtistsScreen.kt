// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.motion.artistArtKey
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.neutralPalette

/**
 * Larger than the 48dp song thumbnail. An artist row carries less information than a
 * song row, so the portrait is what gives the list its rhythm.
 */
private val PORTRAIT_SIZE = 56.dp

/** Gap between the portrait and the labels. */
private val PORTRAIT_GAP = 14.dp

/**
 * Artists as a portrait list rather than bare two-line text: Veldt is an art-forward
 * player, and a column of unadorned strings would be the one browse surface with nothing
 * to look at.
 */
@Composable
fun ArtistsScreen(
    vm: BrowseViewModel,
    onOpenArtist: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, not collectAsState — see AlbumsScreen.
    val artists by vm.artists.collectAsStateWithLifecycle()
    val songs by vm.songs.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    val palette = neutralPalette()

    if (artists.isEmpty()) {
        // Scanning-and-empty is NOT the same as empty, and must not be reported as it.
        if (scanning) {
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)
        } else {
            EmptyState(
                palette = palette,
                title = "No artists yet",
                body = "Artists appear once Veldt has scanned music with artist tags.",
                actionLabel = "Scan library",
                onAction = vm::scan,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        return
    }

    // An artist has no artwork of its own, so one of their tracks lends its cover. The
    // choice is order-independent (see coverTrack), so the portrait no longer moves when
    // the catalogue is re-sorted and the artist page arrives at the same image.
    val portraitByKey: Map<String, Song?> = remember(songs) {
        songs.groupBy { LibraryKeys.artistKey(it) }
            .mapValues { (_, rows) -> rows.coverTrack() }
    }

    // Insets split as in SongsScreen: bottom into contentPadding so rows pass under the
    // translucent bar, top and sides as padding because nothing scrims the status bar.
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
        itemsIndexed(artists, key = { _, artist -> artist.key }) { index, artist ->
            ArtistRow(
                artist = artist,
                portrait = portraitByKey[artist.key],
                artistKey = artist.key,
                palette = palette,
                onClick = { onOpenArtist(artist.key) },
                modifier = Modifier.staggeredEntrance(index, reduced),
            )
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    portrait: Song?,
    artistKey: String,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = DisplayNames.artist(artist.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Matches SongRow's horizontal inset so the three browse tabs share one margin.
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed size, never an unbounded box: ArtImage's loading state fills its parent
        // and would otherwise collapse to ~0 and jump when the bitmap arrives.
        //
        // sharedArt makes this the source of the morph into the artist page's header.
        ArtImage(
            art = portrait?.toSongArt(),
            palette = palette,
            initial = name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .size(PORTRAIT_SIZE)
                .sharedArt(artistArtKey(artistKey))
                .clip(CircleShape),
        )
        // weight(1f) so the labels ellipsize against the row rather than against whatever
        // width the longer of them wants.
        Column(
            Modifier
                .weight(1f)
                .padding(start = PORTRAIT_GAP)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${countOf(artist.albumCount, "album")} · " +
                    countOf(artist.songCount, "song"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
