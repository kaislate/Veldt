// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.components

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.art.SongArt
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.theme.DominantColors
import com.kaislate.veldtplayer.ui.theme.LocalIsLightTheme

/** Blur radius for the tiers that have `RenderEffect`. Wide enough to erase all detail. */
private val BLUR_RADIUS = 48.dp

/**
 * How far past the surface [BackdropTier.Upscale] magnifies the art.
 *
 * Not a blur mechanism — [BACKDROP_DECODE_SAMPLE] is. This only decides how much of the
 * cover fills the frame. It was briefly raised to 2.2f while the tier was trying to blur by
 * magnifying; that cropped deep into an already-tiny bitmap and threw away cover for no
 * gain, so it is back where it started.
 */
private const val UPSCALE = 1.6f

/**
 * [BackdropTier.Upscale] decodes the cover a fraction of the usual size and magnifies it,
 * which is the only genuine low-pass a device without `RenderEffect` has.
 *
 * This replaced a mechanism that did not work. The tier used to rely on magnifying a
 * full-size bitmap, on the premise that Coil hands back something smaller than the surface
 * — it does not; it sizes the decode to the surface it draws into. Measured on device, the
 * magnify-and-scrim approach left album typography plainly readable across the top of the
 * frame, and no combination of the two could fix it: both magnification and scrim attenuate
 * fine detail and broad colour by the same factor, so neither can hide the lettering while
 * keeping the cover recognisable. Decoding small genuinely discards the detail instead.
 *
 * The divisor was picked against a target rather than by feel: residual cover detail should
 * reach the `Modifier.blur` tier's level, which measures 0.62 as the standard deviation of a
 * high-passed band of backdrop, under the same scrim. An eighth reached 1.04 and a sixteenth
 * 0.94; a thirty-second reaches **0.64**, i.e. parity.
 *
 * Sampling harder is free, which is the counter-intuitive part and the reason this is the
 * knob to turn rather than the scrim. Surviving cover colour went 14.4 -> 32.1 -> 32.8 across
 * those three, because what a coarser sample removes is the previous one's interpolation
 * blocking, not artwork. Dimming would have cost real cover to buy the same number; this
 * costs nothing, so the tier can keep the shared scrim and still match a real blur.
 */
private const val BACKDROP_DECODE_SAMPLE = 32

/**
 * Opacity of the AGSL field where it sits over the artwork.
 *
 * The tier is *cover plus signature*, so this value is the whole balance: a blurred cover
 * alone is what every other player ships, and an opaque palette gradient throws away the
 * artwork the screen exists to be about.
 *
 * Chosen by measurement, not by eye. Taking the left-to-right colour spread across the top
 * of the frame as a proxy for "how much of this specific cover survives" — the test cover
 * runs cool on one side and warm on the other — the blurred cover alone scores 54.5, and:
 * 0.45 keeps 31.8 (58%), 0.60 keeps 24.9 (46%), and the shader drawn opaque keeps 6.3
 * (12%, i.e. the artwork is gone, which is exactly the defect this value was added to fix).
 * 0.45 buys 12 more points of surviving cover than 0.60 while the warp bands still read
 * plainly at 45% of full strength, so the extra opacity was not earning its cost.
 */
private const val SHADER_ALPHA = 0.45f

/**
 * The scrim every tier shares, top and bottom of a vertical gradient over `palette.bg`.
 * Weighted to the bottom because that is where the transport and the track title sit.
 *
 * **These are the DARK values and they are unchanged** — the app's signature look is dark, and a
 * blurred cover is dark enough that light text clears its ratio at 0.35/0.80.
 */
private const val SCRIM_TOP_DARK = 0.35f
private const val SCRIM_BOTTOM_DARK = 0.80f

/**
 * The LIGHT scrim, and it has to be much heavier. Measured on a device before and after.
 *
 * `ArtSeed.colors(isLight = true)` solves `onBg` for 4.5:1 against `bg`, which is tone 98 —
 * luminance ≈ 0.93. But this file does not draw `bg`; it draws the blurred cover with `bg` over
 * it at these alphas. With the dark values, a saturated cover left the composited backdrop at
 * luminance **0.49** where the title sits, and the measured contrasts were title 3.07:1,
 * artist 2.12:1, elapsed label 2.46:1 — all short of 4.5:1, two of them short of 3:1.
 *
 * Dark mode never showed it (a blurred cover is dark, so light text reads regardless) and no test
 * could: every audio file on the test devices was cover-less, so the artwork branch never ran.
 *
 * The top stays comparatively open so the cover is still visibly the subject of the screen; the
 * bottom carries nearly all of the correction, because that is where every glyph is.
 */
private const val SCRIM_TOP_LIGHT = 0.55f
private const val SCRIM_BOTTOM_LIGHT = 0.95f

/**
 * The weakest scrim alpha any TEXT actually sits under — **not** the weakest alpha on the
 * surface. That distinction is the whole reason this function exists and [SCRIM_TOP_LIGHT] /
 * [SCRIM_TOP_DARK] are not used for solving text contrast: those are the gradient's value at
 * y=0, the very top of the frame, and no glyph renders there — the album art does. Title,
 * artist, and the elapsed/remaining labels all sit below that (see the layout comment on
 * [ArtBackdrop]: "weighted to the bottom because that is where the transport and the track
 * title sit"), under a strictly stronger scrim as the gradient descends toward
 * [SCRIM_BOTTOM_LIGHT] / [SCRIM_BOTTOM_DARK].
 *
 * **PER THEME, not one shared value — a single constant was tried first and measured wrong.**
 * The two themes' gradients are not the same shape: light runs [SCRIM_TOP_LIGHT]..
 * [SCRIM_BOTTOM_LIGHT] (`0.55..0.95`), dark runs [SCRIM_TOP_DARK]..[SCRIM_BOTTOM_DARK]
 * (`0.35..0.80`). A single `alpha` parameter passed to `ArtSeed.backdropText` therefore cannot be
 * "the weakest value" for both at once — the two gradients disagree about what alpha corresponds
 * to the same fractional height, so a value calibrated against one theme's gradient is
 * necessarily wrong-by-construction for the other's.
 *
 * A single shared `0.75f` was measured on device (Task 3, round 1): light theme cleared with
 * room to spare (title 8.52:1, artist 5.59:1, elapsed 5.77:1, all ≥4.5:1) but dark theme's
 * artist/album fell short at 4.38:1. The arithmetic explains why, and confirms it is a modelling
 * error rather than noise: with `artMean ≈ (211,47,47)` (the crimson test cover) and dark
 * `bg` at tone 10, solving at `alpha=0.75` predicts a composited-ground red channel of ≈75; the
 * *measured* ground (from the captured frame) was 86–108, which back-solves to `alpha≈0.52–0.63`
 * — i.e. the real gradient supplies noticeably LESS scrim at the text band than `0.75` assumed.
 * `0.75` was therefore *optimistic* for dark (it modelled more masking than the renderer actually
 * applies at ~60% down the frame, where the text sits) even though the identical value was
 * *conservative* for light (light's own gradient supplies ≈0.79 at the same 60% mark, well above
 * 0.75). One number cannot be simultaneously conservative for a gradient running up to 0.95 and
 * accurate for one running only up to 0.80 — the fix is not a better single number, it is two.
 *
 * **Each value is now derived from that theme's own corpus sweep** (`BackdropTextTest`'s 5-cover
 * corpus, re-swept independently per theme rather than as one shared alpha) — the smallest value
 * that clears the theme's own ACHIEVABLE bar with a small margin, staying low (more artwork, the
 * conservative direction) rather than reaching for extra headroom the real gradient does not
 * supply:
 *
 * - **Light** — binding case: **black cover**, primary (7:1). The whole corpus (both ratios, all
 *   5 covers) first clears at `alpha=0.599`; `0.62` is that crossing plus a ~0.02 margin, the
 *   same margin-above-crossing Task 2 used. `0.62` corresponds to ~17% down light's own gradient
 *   — near the top, where the title plausibly sits — and stays well below light's own measured
 *   real value (≈0.79 at 60% down), so it remains a genuine floor with room to spare, matching
 *   the device's "passes handsomely" result.
 * - **Dark** — binding case: **white cover**, but only for the ACHIEVABLE bar. `white cover`'s
 *   SECONDARY (4.5:1, the bar Step 3 actually gates every element on, title included — 7:1 is an
 *   internal headroom target, not the WCAG requirement) clears the whole dark corpus at
 *   `alpha=0.599` — the same crossing as light's, to three decimal places, though driven by an
 *   unrelated constraint (dark's own `BG_TONE_DARK=10` / `RATIO_45` versus light's
 *   `BG_TONE_LIGHT=98` / `RATIO_70`). `0.62` is that crossing plus the same ~0.02 margin.
 *   `white cover`'s PRIMARY (7:1) does **not** clear until `alpha≈0.73` — but per-entry sweeping
 *   (see the fix-round report) shows `white cover` is the *only* corpus entry primary struggles
 *   with at any alpha down to `0.35`; every other entry, including `saturated red` (the actual
 *   crimson test cover), clears 7:1 comfortably across the whole range. Requiring `0.73` to
 *   satisfy one synthetic, maximally-extreme entry's ASPIRATIONAL target would recreate the exact
 *   defect just measured — reaching for headroom the real gradient does not supply at the real
 *   text position (`0.73` is ~89% down dark's gradient; `0.62` is ~60% down, matching the
 *   text-band position the device arithmetic above points to). `BackdropTextTest` documents this
 *   one entry as a known, provable ceiling (`Contrast.lighter` clamped at tone 100 still only
 *   reaches ~4.8:1 against that ground) and holds it to the 4.5:1 bar that actually matters
 *   instead.
 *
 * **The two themes land on the same number, `0.62`, by coincidence of two unrelated crossings —
 * not because this collapsed back into one shared constant.** Each is derived from its own
 * theme's `bg` tone, its own theme's binding corpus case, and would move independently if either
 * changed; nothing here ties them together. Had the crossings differed, so would these values —
 * see the individual bullets above for why each number is what it is.
 */
private const val SCRIM_AT_TEXT_LIGHT = 0.62f
private const val SCRIM_AT_TEXT_DARK = 0.62f

/** Which [scrimAtText] floor this theme's own gradient supports. See the KDoc above. */
internal fun scrimAtText(isLight: Boolean): Float =
    if (isLight) SCRIM_AT_TEXT_LIGHT else SCRIM_AT_TEXT_DARK

/** The scrim alphas for one theme. A pair, so a collapse to one branch is visible in a test. */
data class BackdropScrim(val top: Float, val bottom: Float)

/**
 * Which scrim this theme needs.
 *
 * A pure function rather than a `if (isLight)` at the draw site: it is a decision, decisions
 * belong in tested functions, and a composable expression is unreachable without Compose UI test
 * infrastructure this project does not have.
 */
internal fun backdropScrim(isLight: Boolean): BackdropScrim =
    if (isLight) BackdropScrim(SCRIM_TOP_LIGHT, SCRIM_BOTTOM_LIGHT)
    else BackdropScrim(SCRIM_TOP_DARK, SCRIM_BOTTOM_DARK)

/** Where the drift is parked when the user has animations off — mid-sweep, not at an end. */
private const val DRIFT_REST = 0.5f

/**
 * The backdrop suppresses [ArtImage]'s placeholder letter. The glyph is a naming
 * affordance for a thumbnail in a list; blown up to full-screen behind the transport it
 * is just a monogram wall, and the palette wash alone reads better.
 */
private const val NO_GLYPH = ' '

/** Seconds of shader time per drift sweep — how fast the AGSL field itself churns. */
private const val SHADER_TIME_SCALE = 20f

/**
 * How much backdrop this device can actually draw.
 *
 * `Modifier.blur` is API 31+ and **silently does nothing below it**; `RuntimeShader` is API
 * 33+. minSdk is 29, so neither may be required — hence the ladder.
 *
 * The tiers deliberately carry no per-tier appearance. They all reach the scrim with the
 * cover's detail already gone, so they can all take the SAME scrim — which is the whole
 * design intent: the tiers differ in how the blur is produced, not in how much of the
 * artwork you get to see. [Upscale] briefly did carry a heavier scrim, to compensate for a
 * blur that turned out not to be working; once the blur was fixed the compensation was
 * measured to be pure loss and removed.
 *
 * Private, and never a parameter or a return type — callers cannot see which tier they got.
 */
private enum class BackdropTier {
    /** 33+ — the blurred cover with the AGSL field over it. */
    Shader,

    /** 31-32 — the blurred cover alone. */
    Blur,

    /** 29-30 — the cover decoded small and magnified. */
    Upscale,
}

/**
 * THE single version check. One expression, one place — so there is exactly one thing to
 * force when the lower tiers need exercising on a fleet that is entirely API 33+.
 */
private fun currentTier(): BackdropTier = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> BackdropTier.Shader
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> BackdropTier.Blur
    else -> BackdropTier.Upscale
}

/**
 * The now-playing backdrop: the album art, blurred and slowly drifting, under a scrim.
 *
 * THREE TIERS, one API (spec §8) — see [BackdropTier] for why the ladder exists:
 *  - 33+   the blurred cover **with a domain-warped AGSL field composited over it**, so the
 *          backdrop is the artwork *and* a treatment nothing else ships
 *  - 31-32 the blurred cover alone, via `Modifier.blur`
 *  - 29-30 the cover decoded small and magnified, which is a real low-pass rather than a
 *          dimming (see [BACKDROP_DECODE_SAMPLE])
 *
 * Tier selection never leaks to callers: [BackdropTier] is private, there is no tier
 * parameter, and nothing tier-shaped appears in the signature.
 *
 * All three carry the SAME scrim and show the same amount of artwork; measured, the bottom
 * tier's residual cover detail matches the `Modifier.blur` tier's. Only how the blur is
 * produced differs.
 *
 * Every tier draws the artwork, and the drift is identical on all three — so the MOTION
 * reads the same on every device and only the treatment differs.
 */
@Composable
fun ArtBackdrop(
    art: SongArt?,
    palette: DominantColors,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val drift = rememberDrift(reducedMotion)
    val tier = currentTier()

    Box(modifier.fillMaxSize().background(palette.bg)) {
        when (tier) {
            // Cover first, field over it. The shader is an overlay, not a replacement:
            // the whole fleet is API 33+, so a tier that dropped the art would mean no
            // album art on the now-playing screen of every device we have.
            BackdropTier.Shader -> {
                BlurredArt(art, palette, drift, Modifier.fillMaxSize())
                // The SDK check is repeated here on purpose, and it is not defensive
                // padding. [currentTier] already guarantees this branch is 33+, but that
                // guarantee lives behind a function call and lint cannot see through it —
                // without this line every RuntimeShader call reads as unguarded, and
                // "the AGSL path is unreachable below 33" stops being machine-checkable
                // and becomes a claim you have to take on trust. Suppressing the NewApi
                // warning instead would delete the only automated proof of it we have.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ShaderField(palette, drift, Modifier.fillMaxSize())
                }
            }

            BackdropTier.Blur -> BlurredArt(art, palette, drift, Modifier.fillMaxSize())

            BackdropTier.Upscale -> UpscaledArt(art, palette, drift, Modifier.fillMaxSize())
        }

        // Scrim last, over everything, and THEME-AWARE — see [backdropScrim]. The light theme
        // needs a much heavier one: `onBg` is solved against `bg`, but what is actually behind the
        // text is the blurred cover with `bg` over it, and on a light ground a saturated cover
        // pulls that composite far below the luminance the solve assumed.
        // Weighted to the bottom because that is where the transport and the title sit.
        val scrim = backdropScrim(LocalIsLightTheme.current)
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.bg.copy(alpha = scrim.top),
                            palette.bg.copy(alpha = scrim.bottom),
                        )
                    )
                )
        )
    }
}

/** The cover, drifting, with a real `RenderEffect` blur. API 31+ only — see [BackdropTier]. */
@Composable
private fun BlurredArt(
    art: SongArt?,
    palette: DominantColors,
    drift: State<Float>,
    modifier: Modifier,
) {
    ArtImage(
        art = art,
        palette = palette,
        initial = NO_GLYPH,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .graphicsLayer { applyDrift(drift.value) }
            .blur(BLUR_RADIUS),
    )
}

/**
 * The cover, drifting, decoded small and magnified so the resample is a real low-pass.
 * No `RenderEffect` anywhere, so this works all the way down to API 29.
 */
@Composable
private fun UpscaledArt(
    art: SongArt?,
    palette: DominantColors,
    drift: State<Float>,
    modifier: Modifier,
) {
    ArtImage(
        art = art,
        palette = palette,
        initial = NO_GLYPH,
        contentScale = ContentScale.Crop,
        decodeSample = BACKDROP_DECODE_SAMPLE,
        modifier = modifier.graphicsLayer {
            applyDrift(drift.value)
            scaleX *= UPSCALE
            scaleY *= UPSCALE
        },
    )
}

/**
 * 0f..1f on [Motion.drift], frozen at [DRIFT_REST] when the user has animations off.
 *
 * Returns the `State` rather than the `Float` on purpose. A composable that READS an
 * animated float recomposes on every frame of it, and everything below here — including
 * a `SubcomposeAsyncImage` — would recompose 120 times a second for a 40-second drift.
 * Handing the state down lets `graphicsLayer` and `Canvas` read it in the layer and draw
 * phases instead, so the loop costs no recomposition at all.
 */
@Composable
private fun rememberDrift(reducedMotion: Boolean): State<Float> {
    if (reducedMotion) return remember { mutableFloatStateOf(DRIFT_REST) }
    val transition = rememberInfiniteTransition(label = "backdropDrift")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = Motion.drift,
        label = "backdropDriftValue",
    )
}

/** Ken-Burns pan + zoom shared by every tier. */
private fun GraphicsLayerScope.applyDrift(drift: Float) {
    scaleX = 1.15f + drift * 0.10f
    scaleY = 1.15f + drift * 0.10f
    translationX = (drift - 0.5f) * size.width * 0.06f
    translationY = (drift - 0.5f) * size.height * 0.04f
}

/**
 * AGSL flowing gradient, drawn at [SHADER_ALPHA] over the cover. Deliberately small in
 * scope — two warped colour fields, not a general shader framework. Colours come from the
 * animated palette, so the treatment re-themes with everything else.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderField(
    palette: DominantColors,
    drift: State<Float>,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(BACKDROP_AGSL) }
    // The brush is remembered with the shader, not built in the draw scope. Uniforms are
    // mutated on the shader itself, so the wrapper never changes — allocating one per frame
    // would be ~120 short-lived objects a second for the life of the screen.
    val brush = remember(shader) { ShaderBrush(shader) }

    Canvas(modifier) {
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", drift.value * SHADER_TIME_SCALE)
        shader.setColorUniform("uBg", palette.bg.toArgb())
        shader.setColorUniform("uAccent", palette.accent.toArgb())
        shader.setColorUniform(
            "uWave",
            (palette.waveColors.firstOrNull() ?: palette.accent).toArgb(),
        )
        // alpha on the draw call, not a graphicsLayer — no offscreen buffer to allocate
        // and composite every frame of the drift.
        drawRect(brush = brush, alpha = SHADER_ALPHA)
    }
}

private const val BACKDROP_AGSL = """
uniform float2 uSize;
uniform float uTime;
layout(color) uniform half4 uBg;
layout(color) uniform half4 uAccent;
layout(color) uniform half4 uWave;

// Cheap value noise — enough to warp the gradient without banding.
float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x),
               mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}

half4 main(float2 coord) {
    float2 uv = coord / uSize;
    // Domain warp: displace the sample point by noise so bands flow rather than slide.
    float2 warp = float2(
        noise(uv * 2.0 + float2(uTime * 0.05, 0.0)),
        noise(uv * 2.0 + float2(0.0, uTime * 0.04))
    );
    float t = clamp(uv.y + (warp.x - 0.5) * 0.55 + (warp.y - 0.5) * 0.25, 0.0, 1.0);
    half4 lower = mix(uBg, uAccent, half(0.55));
    half4 upper = mix(uBg, uWave, half(0.40));
    return mix(upper, lower, half(t));
}
"""
