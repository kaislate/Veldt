package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.displayAlbumArtist
import com.kaislate.veldtplayer.ui.components.ArtImage
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.motion.albumArtKey
import com.kaislate.veldtplayer.ui.motion.rememberArtMorphActive
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
    val title = DisplayNames.album(songs.first().album)
    val owner = songs.first().displayAlbumArtist()

    // Distance the list has travelled, in pixels, saturating once the header is off-screen.
    // Read inside a graphicsLayer block, so the whole parallax stays on the draw phase —
    // no recomposition per scrolled pixel.
    // Keyed on THIS header's own art. A global "is anything morphing" would also freeze the
    // parallax while the mini-player's cover flies to the now-playing screen.
    val morphing = rememberArtMorphActive(albumArtKey(albumKey))
    val travelledPx: () -> Float = {
        // Held at rest for the length of a morph. The art is not in this header then, it
        // is in the air between two screens, and a header scrolled out of sight must not
        // drag the travelling copy down with it. See rememberArtMorphActive.
        if (morphing() || listState.firstVisibleItemIndex > 0) {
            if (morphing()) 0f else Float.MAX_VALUE
        } else {
            listState.firstVisibleItemScrollOffset.toFloat()
        }
    }

    // Window insets: the artwork bleeds under the status bar on purpose, so the TOP inset
    // is spent on the back button rather than on the layout. The sides are applied,
    // because a landscape cutout would otherwise eat the back button and the track rows.
    val direction = LocalLayoutDirection.current
    Box(
        modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                end = contentPadding.calculateEndPadding(direction),
            )
    ) {
        // The parallax lives on a WRAPPER, deliberately outside the shared element.
        // A graphicsLayer inside the shared node travels into the transition overlay with
        // it, so a header faded to alpha 0 by scrolling made the art fly back to the grid
        // INVISIBLE and the tile popped in at the end of the morph.
        Box(
            Modifier
                .fillMaxWidth()
                .height(HEADER_HEIGHT)
                .graphicsLayer {
                    val travelled = travelledPx().coerceAtMost(size.height)
                    translationY = -travelled * PARALLAX
                    alpha = 1f - travelled / size.height.coerceAtLeast(1f)
                }
        ) {
            ArtImage(
                art = cover?.toSongArt(),
                palette = palette,
                initial = title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
                modifier = Modifier
                    .fillMaxSize()
                    .sharedArt(albumArtKey(albumKey)),
            )
        }

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

        // The scrim disc is drawn ON the button rather than behind a smaller one: nesting
        // IconButton inside a 44dp box squeezed its own 48dp minimum touch target below
        // the interactive minimum, and this is the screen's only navigation affordance.
        // The disc exists at all because the arrow sits on artwork of unknowable
        // brightness — a bare icon vanishes against a pale cover.
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(top = contentPadding.calculateTopPadding())
                .padding(8.dp)
                .align(Alignment.TopStart)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.32f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}
