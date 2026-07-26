package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color

/**
 * THE motion vocabulary for Veldt. Per the plan's global constraints, an animation
 * spec defined anywhere else is a review finding: scattered ad-hoc specs are exactly
 * what makes an app read as generic, and one file is what makes the whole product
 * feel like it moves as a single object.
 */
object Motion {

    /** Transport presses, toggles, chips — a little overshoot so taps feel physical. */
    val snappy: SpringSpec<Float> =
        spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)

    /** Sheets, chrome fades, ambient mode — no overshoot, nothing bouncing at rest. */
    val gentle: SpringSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)

    /** Scrub playhead settling after a drag release — barely-there overshoot. */
    val settle: SpringSpec<Float> =
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)

    /** Shared-element art morph. Duration-based so both ends stay in lockstep. */
    val shared: FiniteAnimationSpec<Float> = tween(durationMillis = 420, easing = FastOutSlowInEasing)

    /** Album-art palette drift on track change (spec §6) — competitors hard-cut here. */
    val palette: AnimationSpec<Color> = tween(durationMillis = 600, easing = LinearOutSlowInEasing)

    /** Per-item delay in a staggered list entrance. */
    const val STAGGER_MS = 28

    /**
     * Beyond this index every item shares the same delay, so long lists don't crawl —
     * and, since those rows gain nothing from animating, it doubles as the cutoff for
     * which rows animate at all. See [staggeredEntrance].
     */
    const val STAGGER_CAP = 10

    /** Vertical distance a list item rises through as it enters, in dp. */
    const val RISE_DP = 8

    /**
     * The system "remove animations" accessibility setting reports as an animator
     * duration scale of exactly 0. Pure so it is unit-testable.
     */
    fun reduced(animatorScale: Float): Boolean = animatorScale == 0f

    fun staggerDelayMs(index: Int, reduced: Boolean): Int =
        if (reduced) 0 else index.coerceIn(0, STAGGER_CAP) * STAGGER_MS
}
