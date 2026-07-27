package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.motion.albumArtKey
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.theme.ColorExtractor

/** How tall the cover stands before the track list begins. */
private val HEADER_HEIGHT = 300.dp

/** Height of the scrim that lands the artwork into the list's own surface colour. */
private val SCRIM_HEIGHT = 140.dp

/**
 * Fraction of the scroll distance the header travels. Below 1 the art lags the list, which
 * is what reads as depth; at 1 it would simply scroll away like any other row.
 */
private const val PARALLAX = 0.5f

/** The back button's tap target, and the diameter of the scrim disc behind it. */
private val BACK_SIZE = 44.dp

/**
 * One album: a full-bleed cover that the track list rises over.
 *
 * The cover is one end of the shared-element morph — the tile tapped on the Albums grid
 * (or in an artist's album strip) travels here rather than the two cross-fading. It then
 * drifts at half the list's speed and fades out as the tracks climb past it. Cheap to
 * implement, and it is the difference between a detail screen that feels built and one
 * that feels generated.
 */
@Composable
fun AlbumDetailScreen(
    vm: BrowseViewModel,
    albumKey: String,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // remember(albumKey): songsForAlbum builds a NEW cold flow per call, so recomposing
    // without this would restart the collection on every frame.
    val songsFlow = remember(albumKey) { vm.songsForAlbum(albumKey) }
    val songs by songsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val palette = ColorExtractor.extract(null)
    val listState = rememberLazyListState()

    if (songs.isEmpty()) {
        // The same three-way distinction the tabs draw. Claiming "this album has no
        // tracks" mid-scan would be the bug Task 8 fixed, reintroduced one screen over —
        // and it is reachable here, because a restored back stack can land on a detail
        // route before the library has been read.
        if (scanning) {
            ScanningState(palette = palette, contentPadding = contentPadding, modifier = modifier)
        } else {
            EmptyState(
                palette = palette,
                title = "Album unavailable",
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

    // The SAME representative track the grid tile drew, so both ends of the morph resolve
    // to one Coil cache entry and the art is already decoded when it lands. See coverTrack.
    val cover = remember(songs) { songs.coverTrack() }
    val title = songs.first().album.trim().ifBlank { "Unknown album" }
    val owner = (songs.first().albumArtist?.takeIf { it.isNotBlank() } ?: songs.first().artist)
        .trim().ifBlank { "Unknown artist" }

    // Distance the list has travelled, in pixels, saturating once the header is off-screen.
    // Reading firstVisibleItem* inside a graphicsLayer block keeps the whole parallax on
    // the draw phase — no recomposition per scrolled pixel.
    val scrolledPx: () -> Float = {
        if (listState.firstVisibleItemIndex == 0) {
            listState.firstVisibleItemScrollOffset.toFloat()
        } else {
            Float.MAX_VALUE
        }
    }

    Box(modifier.fillMaxSize()) {
        // Deliberately NOT inset at the top: the artwork bleeds under the status bar. The
        // back button below takes the inset instead, so nothing collides with the clock.
        ArtImage(
            art = cover?.toSongArt(),
            palette = palette,
            initial = title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .sharedArt(albumArtKey(albumKey))
                .graphicsLayer {
                    val travelled = scrolledPx().coerceAtMost(size.height)
                    translationY = -travelled * PARALLAX
                    alpha = 1f - travelled / size.height.coerceAtLeast(1f)
                },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                bottom = contentPadding.calculateBottomPadding() + LIST_AIR,
            ),
        ) {
            // A spacer the height of the art, ending in a scrim. The gradient is the list's
            // own surface colour fading up into nothing, so the tracks look like they are
            // sliding over the cover rather than sitting in a box beneath it.
            item(key = "header") {
                Column(Modifier.fillMaxWidth().height(HEADER_HEIGHT)) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SCRIM_HEIGHT)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, MaterialTheme.colorScheme.surface)
                                )
                            )
                    )
                }
            }
            item(key = "title") {
                Column(
                    Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$owner · ${countOf(songs.size, "song")}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongRow(
                    song = song,
                    palette = palette,
                    // Play-in-context: the whole album becomes the queue and playback
                    // starts at the tapped position, so track 3 is followed by track 4.
                    onClick = { vm.play(songs, index) },
                    // Opaque, so the parallax art passes BEHIND the tracks rather than
                    // showing through them.
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                    trackLabel = song.trackNumber?.toString() ?: "–",
                )
            }
        }

        // A scrim disc, because the arrow sits on artwork whose brightness is unknowable —
        // a bare icon disappears against a pale cover.
        Box(
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding())
                .padding(8.dp)
                .align(Alignment.TopStart)
                .size(BACK_SIZE)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
        }
    }
}
