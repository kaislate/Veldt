package com.kaislate.veldtplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.motion.albumArtKey
import com.kaislate.veldtplayer.ui.motion.sharedArt
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** One card in a horizontal shelf of records. */
private val CARD_WIDTH = 148.dp

/** The card's touch/ripple shape; the cover is clipped slightly tighter inside it. */
private val CARD_SHAPE = RoundedCornerShape(18.dp)
private val COVER_SHAPE = RoundedCornerShape(14.dp)

/**
 * THE album card used by every horizontal shelf of records — the artist page's discography
 * and the search screen's Albums section — so the two shelves share one set of proportions
 * and one morph identity instead of drifting apart.
 *
 * [cover] must be chosen by `coverTrack()` over the album's FULL track list, not over
 * whatever subset the calling screen holds: both ends of the art morph have to resolve to
 * the same [com.kaislate.veldtplayer.data.art.SongArt] or the transition reads as a swap.
 *
 * [caption] is the record's owner where a shelf can hold two same-titled albums (search),
 * and null on a shelf that already belongs to one artist.
 */
@Composable
fun AlbumCard(
    albumKey: String,
    title: String,
    cover: Song?,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Column(
        modifier = modifier
            .width(CARD_WIDTH)
            // Clip before clickable, so the ripple stops at the card's corners rather than
            // painting a rectangle across the artwork.
            .clip(CARD_SHAPE)
            .clickable(onClick = onClick)
            .padding(bottom = 8.dp),
    ) {
        // aspectRatio, never an unbounded height: ArtImage's loading state fills its parent
        // and would otherwise collapse to ~0 and jump when the bitmap arrives.
        //
        // sharedArt sits BEFORE clip so the clip travels with the shared node; applied
        // outside it, the cover would square off for the length of the morph.
        ArtImage(
            art = cover?.toSongArt(),
            palette = palette,
            initial = title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .sharedArt(albumArtKey(albumKey))
                .clip(COVER_SHAPE),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
