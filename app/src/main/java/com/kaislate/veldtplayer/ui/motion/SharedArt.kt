// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
 * that is not a destination must NOT reach for a borrowed scope — see [sharedSongArt].
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
 *
 * That id is the Room surrogate `songs.id` (see `SongArt`), and a morph key is the most
 * short-lived identity in the app — it has to be unique only among elements composed at the same
 * instant. The surrogate is more than sufficient; `sourceId:externalId` here would be a longer
 * string buying nothing.
 */
fun songArtKey(songId: Long): String = "song-art:$songId"

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

/** [rememberArtMorph] for the track cover, hoisted so more than one thing can ask about it. */
@Composable
fun rememberSongArtMorph(songId: Long?): ArtMorph? =
    // No song is no identity: marking a placeholder would let two DIFFERENT empty surfaces
    // claim the same element.
    if (songId == null) null else rememberArtMorph(songArtKey(songId))

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
 * **Per-element, not global.** `isTransitionActive` alone was exact while the app had one
 * morph; the track cover is a second concurrent shared element, and a navigation that morphs
 * it would otherwise freeze an album header's parallax at whatever offset it happened to be
 * scrolled to. `isMatchFound` is the qualifier — but only on the instance a modifier actually
 * attached, which is why this takes the state rather than a key. See [rememberArtMorph].
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
 * How long to wait for this element's own morph to START before concluding that [routeKey]'s
 * change caused none.
 *
 * **Sized for the slowest device this app supports, not for the one it was measured on.** The
 * reference phone dispatches a navigation's first transition frame ~180-195ms after the tap
 * (measured on both legs), and that lead-in is composition and navigation dispatch, not
 * animation — it does not shrink when the spec does. `minSdk 29` is set where it is because
 * the fleet includes Nexus 7-class tablets, where a cold destination composition can plausibly
 * run several times that; 250ms (~15 frames) left no margin for them at all. 600ms is ~3x the
 * measured lead-in, i.e. it still expires promptly on a device three times slower than the
 * reference.
 *
 * The other side of the trade is weak, which is what makes the generous value cheap: when this
 * expires wrongly the cost is a mini-player that stays COMPOSED a little longer than needed —
 * invisible, non-clickable and out of the accessibility tree by then (see `MiniPlayer.visible`)
 * — whereas expiring EARLY drops the morph's end mid-flight and snaps the cover. The two
 * failure modes are not the same size, so the timeout is biased towards the survivable one.
 */
private const val MORPH_START_TIMEOUT_MS = 600L

/**
 * A hard ceiling on how long an end may linger once a transition HAS started, so a
 * transition that never reports itself finished cannot park an invisible end in the tree
 * forever.
 *
 * **Derived from [Motion.SHARED_BOUNDS_MS] rather than written as a magnitude**, so the
 * relationship it depends on — comfortably longer than the morph it must never truncate — is
 * now a compile-time one. It used to be a bare `3_000L` with the constraint stated only in
 * prose, and probing the morph at 2500ms is exactly the edit that walks into it.
 */
private const val MORPH_RUN_TIMEOUT_MS = Motion.SHARED_BOUNDS_MS * 7L

/**
 * Whether a caller-managed shared element whose [routeKey] just changed must still be
 * COMPOSED even though it is no longer the visible end.
 *
 * **This exists because "keep both ends composed forever" is the wrong contract.** A shared
 * element stays in the UI tree when `visible == false`, and it starts a transition whenever
 * its size or position changes *while it has an active match*. A bounds change on a live
 * match is the trigger — registration alone is not. An end that has been sitting in the tree
 * all along, at the same size and the same place, therefore has nothing to change and fires
 * nothing: the departing end holds its bounds in the overlay until it leaves composition and
 * the resident end then simply draws where it always was. That is the snap. Compose's own
 * guidance is to remove a `visible == false` end once the transition is finished, and this is
 * what lets a caller do that.
 *
 * Used as `visible || rememberMorphLinger(routeKey)`, an end is ABSENT while the other one
 * owns the screen and is re-attached at frame 0 of the return — a freshly measured end
 * against a live match, which is exactly the shape the outbound leg already has.
 *
 * **Why `remember(routeKey)` and not a `LaunchedEffect`.** This must already read true in the
 * SAME composition that first sees the new [routeKey]. An effect runs after that frame, by
 * which time the end being kept alive has already left the tree and taken the morph's start
 * bounds with it — which is the trap in the naive `visible || scope.isTransitionActive`,
 * where the transition is not yet active on the frame the route flips.
 *
 * **Why [morph] and not just the route.** `isTransitionActive` is scope-global: it is true for
 * ANY shared transition anywhere under the one `SharedTransitionLayout`, including an
 * album-list -> album-detail morph that is already running when [routeKey] flips. Waiting on it
 * alone would latch onto that transition, and then stop lingering when THAT one ended —
 * possibly before this element's own morph had started, dropping the end mid-flight. Qualifying
 * on this morph's own `isMatchFound` is the same fix, for the same reason, as
 * [rememberArtMorphActive]; it is only answerable on a state a modifier actually attached,
 * which is why the caller hoists one and hands it to both. See [ArtMorph].
 *
 * Only the START is qualified. Once this element's own morph is confirmed running, the wait for
 * the end is the unqualified `!isTransitionActive`, which can only resolve at or after that
 * morph finishes — a superset, and so incapable of truncating it. Waiting instead for the match
 * to drop would end the linger when the OTHER end leaves composition, which on the return leg
 * happens with a third of the travel still to run.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun rememberMorphLinger(routeKey: Any?, morph: ArtMorph?): Boolean {
    val transition = LocalSharedTransitionScope.current
    if (transition == null || morph == null) return false
    val linger = remember(routeKey) { mutableStateOf(true) }
    LaunchedEffect(routeKey, morph) {
        val started = withTimeoutOrNull(MORPH_START_TIMEOUT_MS) {
            snapshotFlow { transition.isTransitionActive && morph.state.isMatchFound }.first { it }
        }
        if (started != null) {
            withTimeoutOrNull(MORPH_RUN_TIMEOUT_MS) {
                snapshotFlow { transition.isTransitionActive }.first { !it }
            }
        }
        linger.value = false
    }
    return linger.value
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

/**
 * Marks this art as one end of the TRACK cover's morph — the mini-player thumbnail and the
 * now-playing screen's full-bleed art.
 *
 * **Caller-managed visibility, not an [AnimatedVisibilityScope].** The mini-player is chrome:
 * it has no `composable { }` receiver, so hanging it off an `AnimatedVisibility` meant
 * borrowing a scope that belonged to something else, and its end then existed only while that
 * chrome was composed — true going OUT, false coming BACK, because the chrome was composed by
 * the very transition it was meant to be an end of. Declaring visibility directly removes the
 * borrowed scope and puts the answer where the caller already knows it.
 *
 * **Registration is necessary and not sufficient, and the caller owns the rest.** An end that
 * simply stays composed forever at unchanged bounds never triggers anything — see
 * [rememberMorphLinger] for why, and for the lifetime the caller must give this instead: an
 * end must be ABSENT while the other owns the screen, and re-attached at frame 0 of the
 * return.
 *
 * [visible] is which END is the live one, so exactly one of the pair passes true.
 *
 * Takes a hoisted [ArtMorph] rather than minting its own, so the caller that has to decide this
 * end's LIFETIME can ask the same state whether it has a match — see [rememberMorphLinger].
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedSongArt(morph: ArtMorph?, visible: Boolean): Modifier {
    val transition = LocalSharedTransitionScope.current
    if (transition == null || morph == null) return this
    return with(transition) {
        this@sharedSongArt.sharedElementWithCallerManagedVisibility(
            sharedContentState = morph.state,
            visible = visible,
            boundsTransform = { _, _ -> Motion.sharedBounds },
        )
    }
}

/** [sharedSongArt] for the end that only marks itself and never asks about the morph. */
@Composable
fun Modifier.sharedSongArt(songId: Long?, visible: Boolean): Modifier =
    sharedSongArt(rememberSongArtMorph(songId), visible)
