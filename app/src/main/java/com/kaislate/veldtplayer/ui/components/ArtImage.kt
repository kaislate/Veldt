// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.kaislate.veldtplayer.data.art.ArtDecode
import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.data.art.artDecodeSample
import com.kaislate.veldtplayer.ui.theme.DisplayFamily
import com.kaislate.veldtplayer.ui.theme.DominantColors

/** Glyph height as a fraction of the placeholder's shorter side. */
private const val GLYPH_FRACTION = 0.36f

/**
 * Cap on the box side the glyph is derived from. [BoxWithConstraints] reports
 * `Dp.Infinity` under an unbounded parent, which would otherwise yield an infinite
 * font size; the cap also keeps the glyph sane on tablet-sized full-screen art.
 */
private val GLYPH_MAX_BOX = 512.dp

/**
 * THE album-art composable. Every surface uses it so art loading, placeholders and
 * cache behaviour are identical everywhere.
 *
 * [decodeSample] is the one knob, and it is opt-in: at [ArtDecode.FULL] this passes the
 * bare [SongArt] exactly as it always has, so every existing caller keeps the same request,
 * the same cache entry and the same bitmap. Only a caller that explicitly wants a smaller
 * decode — today just the now-playing backdrop, which needs a genuine low-pass on devices
 * with no `RenderEffect` — builds a request, and that request lands in its own cache entry.
 * See [ArtDecode] for why the separation matters to the art morph.
 */
@Composable
fun ArtImage(
    art: SongArt?,
    palette: DominantColors,
    initial: Char,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    decodeSample: Int = ArtDecode.FULL,
) {
    if (art == null) {
        ArtPlaceholder(initial, palette, modifier)
        return
    }
    val context = LocalContext.current
    val model = remember(art, decodeSample, context) {
        if (decodeSample == ArtDecode.FULL) {
            art
        } else {
            ImageRequest.Builder(context).data(art).artDecodeSample(decodeSample).build()
        }
    }
    SubcomposeAsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            // Loading draws the wash ALONE. The glyph means "there is no art", not
            // "art is one frame away" — with crossfade off, drawing it during a load
            // makes a re-decoded row hard-cut from letter to cover, a visible pop.
            is AsyncImagePainter.State.Loading -> ArtWash(palette, Modifier.fillMaxSize())
            // Error / Empty: resolution finished and there is no art. Wash plus glyph.
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
    BoxWithConstraints(
        modifier = modifier.background(paletteWash(palette)),
        contentAlignment = Alignment.Center,
    ) {
        // Box-relative, never a fixed sp. A fixed size reads as an accident on the
        // full-screen art, and because sp tracks the user's font scale it overflows a
        // 48dp row thumbnail at large scales (Box does not clip). Converting Dp -> Sp
        // divides that scale back out, so the glyph stays this fraction of the box.
        val side: Dp = minOf(maxWidth, maxHeight, GLYPH_MAX_BOX)
        val glyphSize = with(LocalDensity.current) { (side * GLYPH_FRACTION).toSp() }
        Text(
            text = initial.uppercaseChar().toString(),
            color = palette.onBg.copy(alpha = 0.75f),
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.Bold,
            fontSize = glyphSize,
            textAlign = TextAlign.Center,
        )
    }
}

/** The palette wash alone — the shared ground under both placeholder states. */
@Composable
private fun ArtWash(palette: DominantColors, modifier: Modifier = Modifier) {
    Box(modifier.background(paletteWash(palette)))
}

/**
 * The palette wash — the ground under both placeholder states, and the same gradient the
 * browse empty/scanning surfaces tint themselves with.
 *
 * [alpha] scales the whole wash toward transparent so one gradient can serve both jobs:
 * at 1f it is the opaque thumbnail ground, and at a low value it is a veil laid over the
 * theme's own surface. Full-screen the opaque form would paint a near-black panel across
 * a light-themed app; scaling it keeps the palette's presence without fighting the theme.
 */
internal fun paletteWash(palette: DominantColors, alpha: Float = 1f): Brush =
    Brush.linearGradient(
        listOf(
            palette.accent.copy(alpha = 0.55f * alpha),
            palette.bg.copy(alpha = alpha),
        )
    )
