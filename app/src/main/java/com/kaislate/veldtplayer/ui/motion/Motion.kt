package com.kaislate.veldtplayer.ui.motion

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Rect
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

    /**
     * How long [sharedBounds] takes, in milliseconds — named rather than written inline
     * because it is the ONE number the morph's plumbing has to agree with and
     * `FiniteAnimationSpec` exposes no duration to read back.
     *
     * `ui/motion/SharedArt.kt` derives its "a transition that never reports itself finished
     * must not park an invisible end in the tree forever" ceiling from this, so lengthening
     * the morph can no longer silently leave that ceiling too low to cover it. That is the
     * entire reason a magnitude lives in the vocabulary next to the spec it feeds.
     */
    const val SHARED_BOUNDS_MS = 420

    /**
     * Shared-element art morph: the spec a cover travels between two destinations on.
     * Duration-based rather than a spring so both ends stay in lockstep.
     *
     * Rect-typed because that is what `BoundsTransform` animates. The float-typed `shared`
     * that used to sit here was written for a fallback cross-fade the Task 8 spike proved
     * unnecessary, and it is deleted rather than kept "for later" — an unused spec in the
     * motion vocabulary is a suggestion that something moves that way when nothing does.
     * This exists so a call site SELECTS a spec instead of declaring one; a `tween` written
     * next to a `sharedElement` is the exact scattering this file prevents.
     */
    val sharedBounds: FiniteAnimationSpec<Rect> = tween(
        durationMillis = SHARED_BOUNDS_MS,
        easing = FastOutSlowInEasing,
    )

    /** Album-art palette drift on track change (spec §6) — competitors hard-cut here. */
    val palette: AnimationSpec<Color> = tween(durationMillis = 600, easing = LinearOutSlowInEasing)

    /**
     * The now-playing backdrop's ambient drift: 0f..1f across 40 seconds, then back.
     *
     * Lives here rather than inline at the `rememberInfiniteTransition` call site for the
     * reason this file exists at all — it is an `AnimationSpec`, and the moment one is
     * written next to its consumer the vocabulary stops being a vocabulary. Linear because
     * an eased loop visibly pauses at each turn, which reads as a stutter rather than as
     * drift; Reverse rather than Restart because a restart snaps the artwork back across
     * the frame. Deliberately slower than anything else here: at 40s the backdrop is
     * something you notice having moved, never something you watch move.
     */
    val drift: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(durationMillis = 40_000, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    )

    /**
     * The wave scrub bar's phase sweep: one loop every 26 seconds, linear, restarting.
     *
     * Here rather than inline for the same reason as [drift] — it is an `AnimationSpec`, and
     * the vocabulary stops being a vocabulary the moment one is written next to its consumer.
     *
     * LOAD-BEARING — this duration and [WAVE_PHASE_TURNS] are a matched pair, not two
     * independent knobs, which is why they sit together. Every renderer in
     * `ui/components/WaveStyles.kt` picks its internal rates as multiples that land on a
     * whole number of periods at 20π, so the wrap is invisible. Change either number without
     * the other and all twenty styles visibly restart once per loop. Linear for a related
     * reason: an eased sweep would stall the phase at each wrap, which reads as the wave
     * hitching rather than flowing.
     *
     * Restart rather than Reverse because phase is a monotonic sweep — reversing it would run
     * every wave backwards for half the cycle.
     */
    val wavePhase: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
        animation = tween(durationMillis = 26_000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )

    /**
     * Half-turns of phase per [wavePhase] loop: the sweep runs 0f..`WAVE_PHASE_TURNS * PI`.
     *
     * A raw magnitude rather than a spec, and kept here anyway — same as [RISE_DP] — because
     * it is only correct *relative to* the duration above. Splitting the pair across two
     * files would make each half look independently tunable, which is exactly the edit that
     * breaks every wave style at once.
     */
    const val WAVE_PHASE_TURNS = 20f

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
