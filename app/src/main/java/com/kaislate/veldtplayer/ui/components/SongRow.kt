package com.kaislate.veldtplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.art.toSongArt
import com.kaislate.veldtplayer.data.library.displayAlbum
import com.kaislate.veldtplayer.data.library.displayArtist
import com.kaislate.veldtplayer.data.library.displayTitle
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** The side of the leading slot — artwork or track index, so both lists share a rhythm. */
private val LEADING_SIZE = 48.dp

/**
 * The one song row used by every list, so art, spacing and truncation stay identical.
 *
 * [trackLabel], when given, takes the artwork's place. An album page is the one surface
 * where every row's cover is the cover already filling the header: a dozen copies of one
 * image reads as noise, and the position on the record is the thing the eye is looking for
 * there. Null everywhere else, so every other list keeps its thumbnails.
 */
@Composable
fun SongRow(
    song: Song,
    palette: DominantColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackLabel != null) {
            // Same footprint as the thumbnail, so a track list and a song list line their
            // titles up at the same x even though one has art and the other does not.
            Box(
                modifier = Modifier.size(LEADING_SIZE),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = trackLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        } else {
            // A fixed 48dp box, never an unbounded one: ArtImage's loading state fills its
            // parent, so under unbounded constraints it would collapse to ~0 and jump when
            // the bitmap arrives.
            ArtImage(
                art = song.toSongArt(),
                palette = palette,
                initial = song.title.firstOrNull { it.isLetterOrDigit() } ?: '♪',
                modifier = Modifier
                    .size(LEADING_SIZE)
                    .clip(RoundedCornerShape(8.dp)),
            )
        }
        // weight(1f) so the two labels ellipsize against the row rather than against
        // whatever width the longer of them happens to want.
        Column(
            Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = song.displayTitle(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.displayArtist()} · ${song.displayAlbum()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
