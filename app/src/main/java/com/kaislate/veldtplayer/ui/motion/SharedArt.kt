// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
 * The current nav destination's own transition scope. Provided per `composable { }`.
 *
 * Only [sharedArt] needs it, and only because a nav destination genuinely has one. Chrome
 * that is not a destination must NOT reach for a borrowed scope: an element composed by the very
 * transition it is supposed to be an end of exists on only one leg of it. (That is what the old
 * mini-player <-> now-playing cover morph ran into; now-playing is a sheet today and its cover is
 * driven by the sheet's own fraction instead — see `ui/nowplaying/PlayerSheet.kt`.)
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
 * The identity of ONE morph, created once and then shared by everything that has an opinion
 * about it.
 *
 * **Hoisted, and that is the whole point of the type existing.** `rememberSharedContentState`
 * mints a NEW state per call site, and `isMatchFound` is backed by an internal field that
 * only the `sharedElement*` modifiers ever write. A state that was never handed to a modifier
 * therefore reports `false` forever — so a screen that calls `rememberSharedContentState`
 * a second time just to ask "am I morphing?" gets a constant `false` and a guard that
 * silently never fires. One instance, passed to both [Modifier.sharedArt] and
 * [rememberArtMorphActive], is the only arrangement in which that question can be answered.
 *
 * Null outside a [SharedTransitionScope] — a preview or a test host — where every consumer
 * degrades to a no-op rather than throwing.
 *
 * A wrapper type rather than the raw `SharedContentState` so the experimental opt-in stops
 * HERE. Screens hold an [ArtMorph] and never annotate themselves, which is the same reason
 * the two scopes travel as CompositionLocals instead of as parameters.
 *
 * `@Stable`, deliberately NOT `@Immutable`: the wrapped `SharedContentState.isMatchFound` is
 * snapshot-backed mutable state that the `sharedElement*` modifiers write. Today the only
 * read is inside a draw-phase lambda, so the difference is invisible — but `@Immutable` is a
 * promise the compiler acts on, and the first composable that reads `isMatchFound` directly
 * in composition would be marked skippable and then never invalidate when the match changes.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Stable
class ArtMorph internal constructor(
    internal val state: SharedTransitionScope.SharedContentState,
)

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberArtMorph(key: String): ArtMorph? {
    val transition = LocalSharedTransitionScope.current ?: return null
    val state = with(transition) { rememberSharedContentState(key = key) }
    return remember(state) { ArtMorph(state) }
}

/**
 * Whether [morph] is in flight right now, as a lambda so callers read it in the draw phase
 * instead of recomposing on it.
 *
 * A screen needs this to stop applying its own scroll effects to art that is mid-morph. A
 * shared element is drawn into the transition overlay, which BYPASSES the draw modifiers
 * wrapping it in the normal tree — but any modifier inside the shared node travels with it.
 * So a fade meant to hide a scrolled-away header also hid the artwork all the way back to
 * the grid. Holding the effect at rest for the length of the morph is the honest fix: the
 * art is not in the header during a transition, it is in the air.
 *
 * **Per-element, not global.** `isTransitionActive` is true for ANY shared transition under the
 * one `SharedTransitionLayout`, so an unrelated morph running while this header is composed (an
 * artist's album strip into a different album, say) would otherwise freeze its parallax at
 * whatever offset it happened to be scrolled to. `isMatchFound` is the qualifier — but only on
 * the instance a modifier actually attached, which is why this takes the state rather than a
 * key. See [rememberArtMorph].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberArtMorphActive(morph: ArtMorph?): () -> Boolean {
    val transition = LocalSharedTransitionScope.current
    if (transition == null || morph == null) return { false }
    return remember(transition, morph) {
        { transition.isTransitionActive && morph.state.isMatchFound }
    }
}

/**
 * Marks this art as one end of [morph], for an element that lives in a NAV DESTINATION.
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
fun Modifier.sharedArt(morph: ArtMorph?): Modifier {
    val transition = LocalSharedTransitionScope.current
    val visibility = LocalNavAnimatedVisibilityScope.current
    if (transition == null || morph == null || visibility == null) return this
    return with(transition) {
        this@sharedArt.sharedElement(
            morph.state,
            animatedVisibilityScope = visibility,
            // Selects the spec; never declares one. See Motion.sharedBounds.
            boundsTransform = { _, _ -> Motion.sharedBounds },
        )
    }
}

/** [sharedArt] for the many call sites that only mark an element and never ask about it. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedArt(key: String): Modifier = sharedArt(rememberArtMorph(key))
