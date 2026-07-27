package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The album-art morph: a cover tapped on a browse surface travels into the detail screen's
 * header instead of the two cross-fading past each other.
 *
 * A shared element needs two scopes — the [SharedTransitionScope] that owns the overlay and
 * the [AnimatedVisibilityScope] of the destination it lives in. Both are PUBLISHED here
 * rather than threaded through every screen signature. Threading them would put two
 * experimental-API parameters on `AlbumsScreen`, `ArtistsScreen` and both detail screens
 * purely so one `ArtImage` deep inside each could reach them, and would make every
 * `@Preview` of those screens unwritable.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The current nav destination's own transition scope. Provided per `composable { }`. */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * The morph identity for one album's artwork. Built from the normalized album key, so the
 * grid tile, the artist screen's album strip and the detail header all name the same
 * element without agreeing on anything but the key they already navigate by.
 */
fun albumArtKey(albumKey: String): String = "album-art:$albumKey"

/** As [albumArtKey], for the borrowed cover an artist is represented by. */
fun artistArtKey(artistKey: String): String = "artist-art:$artistKey"

/**
 * Marks this art as one end of the morph named [key].
 *
 * No-ops when either scope is absent instead of throwing. The only caller that can be
 * missing them is a preview or a test host — outside a transition a shared element renders
 * identically anyway, so failing loudly would buy nothing and cost every preview.
 *
 * Both ends must resolve to the same [com.kaislate.veldtplayer.data.art.SongArt], or the
 * morph animates between two different Coil cache entries and reads as a swap. See
 * `List<Song>.coverTrack()`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String): Modifier {
    val transition = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedVisibilityScope.current
    if (transition == null || visibility == null) return this
    return with(transition) {
        this@sharedArt.sharedElement(
            rememberSharedContentState(key = key),
            animatedVisibilityScope = visibility,
            // Selects the spec; never declares one. See Motion.sharedBounds.
            boundsTransform = { _, _ -> Motion.sharedBounds },
        )
    }
}
