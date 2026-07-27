package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
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

/**
 * The transition scope of whatever the art currently lives in — provided per `composable { }`
 * for a nav destination, and by the bottom chrome's own `AnimatedVisibility` for the
 * mini-player, which is not a destination and so has no `composable { }` receiver to borrow.
 */
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
 * The morph identity for ONE TRACK's cover — the mini-player's thumbnail and the
 * now-playing screen's full-bleed art, which are the same song and so resolve to the same
 * [com.kaislate.veldtplayer.data.art.SongArt].
 *
 * Keyed on the song id rather than the album key on purpose: what travels here is the
 * playing track's cover, and two tracks on one record must not be able to name the same
 * element while both are on screen.
 */
fun songArtKey(songId: Long): String = "song-art:$songId"

/**
 * Whether the morph named [key] is in flight right now, as a lambda so callers read it in
 * the draw phase instead of recomposing on it.
 *
 * A screen needs this to stop applying its own scroll effects to art that is mid-morph. A
 * shared element is drawn into the transition overlay, which BYPASSES the draw modifiers
 * wrapping it in the normal tree — but any modifier inside the shared node travels with it.
 * So a fade meant to hide a scrolled-away header also hid the artwork all the way back to
 * the grid. Holding the effect at rest for the length of the morph is the honest fix: the
 * art is not in the header during a transition, it is in the air.
 *
 * **Per-element, not global.** `isTransitionActive` alone was exact while the app had one
 * morph; the mini-player added a SECOND concurrent shared element, and a navigation that
 * morphs the mini-player's cover would otherwise freeze an album header's parallax at
 * whatever offset it happened to be scrolled to. [isMatchFound] is the qualifier: it is true
 * only while some other surface is claiming this same key, i.e. only while THIS art is the
 * thing travelling.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberArtMorphActive(key: String): () -> Boolean {
    val transition = LocalSharedTransitionScope.current ?: return { false }
    val state = with(transition) { rememberSharedContentState(key = key) }
    return remember(transition, state) {
        { transition.isTransitionActive && state.isMatchFound }
    }
}

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

/**
 * [sharedArt] for a surface whose subject may be absent: with nothing playing there is no
 * track to name, and marking the placeholder as one end of a morph would let two DIFFERENT
 * empty surfaces claim the same identity.
 */
@Composable
fun Modifier.sharedSongArt(songId: Long?): Modifier =
    if (songId == null) this else this.sharedArt(songArtKey(songId))
