package com.kaislate.veldtplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.ui.theme.DisplayFamily
import com.kaislate.veldtplayer.ui.theme.DominantColors

/**
 * THE album-art composable. Every surface uses it so art loading, placeholders and
 * cache behaviour are identical everywhere.
 */
@Composable
fun ArtImage(
    art: SongArt?,
    palette: DominantColors,
    initial: Char,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (art == null) {
        ArtPlaceholder(initial, palette, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = art,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            else -> ArtPlaceholder(initial, palette, Modifier.fillMaxSize())
        }
    }
}

/**
 * Not a grey box. The placeholder is built from the CURRENT animated palette plus the
 * track's initial, so an art-less library still looks composed and still re-themes
 * per track (spec §4).
 */
@Composable
fun ArtPlaceholder(
    initial: Char,
    palette: DominantColors,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    palette.accent.copy(alpha = 0.55f),
                    palette.bg,
                )
            )
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.uppercaseChar().toString(),
            color = palette.onBg.copy(alpha = 0.75f),
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
        )
    }
}
