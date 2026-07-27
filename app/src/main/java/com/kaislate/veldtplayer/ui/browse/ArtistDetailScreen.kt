package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.motion.albumArtKey
import com.kaislate.veldtplayer.ui.motion.artistArtKey
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The header portrait — big enough to anchor the name, not so big it becomes the screen. */
private val PORTRAIT_SIZE = 96.dp

/** One album card in the strip. */
private val CARD_WIDTH = 148.dp

/** Side margin shared with the album strip's first card, so the two align. */
private val SIDE_MARGIN = 16.dp

/**
 * One artist: their records as a strip of covers, then every track they appear on.
 *
 * The strip exists because an artist page that is only a long list of songs throws away the
 * one thing this library has plenty of — artwork — and because tapping a record is how
 * people actually navigate an artist. Each cover is a morph source into the album page.
 */
@Composable
fun ArtistDetailScreen(
    vm: BrowseViewModel,
    artistKey: String,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // remember(artistKey): songsForArtist builds a NEW cold flow per call.
    val songsFlow = remember(artistKey) { vm.songsForArtist(artistKey) }
    val songs by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val palette = ColorExtractor.extract(null)

    if (songs.isEmpty()) {
        // Scanning-and-empty is not the same as empty; see AlbumDetailScreen.
        if (scanning) {
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)
        } else {
            EmptyState(
                palette = palette,
                title = "Artist unavailable",
                body = "These tracks are no longer in the library. They may have been " +
                    "deleted or moved off the device.",
                actionLabel = "Go back",
                onAction = onBack,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        return
    }

    val name = songs.first().artist.trim().ifBlank { "Unknown artist" }
    // The portrait the Artists row drew, reached by the same order-independent rule, so the
    // two are one image and the morph is continuous rather than a swap.
    val portrait = remember(songs) { songs.coverTrack() }

    // Keyed on the COMPOUND album key, never the title: it is what the route expects, and a
    // title-only key would merge two same-titled records into one card. songsForArtist is
    // already album-major, so first-per-key preserves that order.
    val albums: List<ArtistAlbum> = remember(songs) {
        songs.groupBy { LibraryKeys.albumKey(it) }
            .map { (key, rows) ->
                ArtistAlbum(
                    key = key,
                    name = rows.first().album.trim().ifBlank { "Unknown album" },
                    cover = rows.coverTrack(),
                )
            }
    }

    // Insets split as on the tabs: the bottom one becomes contentPadding so tracks pass
    // under the translucent bar; top and sides stay padding, since nothing scrims them.
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
            bottom = contentPadding.calculateBottomPadding() + LIST_AIR,
        ),
    ) {
        item(key = "header") {
            Column {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A fixed size, never unbounded: ArtImage's loading state fills its
                    // parent and would otherwise collapse to ~0 and jump when art lands.
                    ArtImage(
                        art = portrait?.toSongArt(),
                        palette = palette,
                        initial = name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
                        modifier = Modifier
                            .size(PORTRAIT_SIZE)
                            .sharedArt(artistArtKey(artistKey))
                            .clip(CircleShape),
                    )
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${countOf(albums.size, "album")} · " +
                                countOf(songs.size, "song"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        item(key = "albums") {
            SectionLabel("Albums")
            LazyRow(
                contentPadding = PaddingValues(horizontal = SIDE_MARGIN),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(albums, key = { it.key }) { album ->
                    AlbumCard(
                        album = album,
                        palette = palette,
                        onClick = { onOpenAlbum(album.key) },
                    )
                }
            }
        }

        item(key = "songs") { SectionLabel("Songs") }

        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            // Play-in-context over the artist's whole catalogue, in the order shown.
            SongRow(song = song, palette = palette, onClick = { vm.play(songs, index) })
        }
    }
}

/** One record on an artist's shelf. */
private data class ArtistAlbum(val key: String, val name: String, val cover: Song?)

@Composable
private fun AlbumCard(
    album: ArtistAlbum,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            // Clip before clickable, so the ripple stops at the card's corners rather than
            // painting a rectangle across the artwork.
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        ArtImage(
            art = album.cover?.toSongArt(),
            palette = palette,
            initial = album.name.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArt(albumArtKey(album.key))
                .clip(RoundedCornerShape(14.dp)),
        )
        Text(
            text = album.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Quiet divider-by-typography: two sections in one scroll need naming, not a rule line. */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = SIDE_MARGIN, top = 20.dp, bottom = 10.dp),
    )
}
