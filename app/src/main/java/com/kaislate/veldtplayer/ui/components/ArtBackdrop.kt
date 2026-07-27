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

/** Blur radius for the API 31-32 tier. Wide enough that no edge of the art is readable. */
private val BLUR_RADIUS = 48.dp

/**
 * How far past the surface the API 29-30 tier magnifies the art, so resampling softens it.
 *
 * Honest about what this buys: Coil sizes its bitmap to the surface it is drawn into, so a
 * full-screen backdrop gets a full-screen bitmap and magnifying it 1.6x only blurs by the
 * resample. Forcing this tier on device (Task 12) left large album typography still
 * readable — it is a softening, not a blur. Acceptable because it is the floor tier and
 * nothing in the fleet reaches it; the scrim, not the upscale, is what keeps text legible.
 */
private const val UPSCALE = 1.6f

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
 * The now-playing backdrop: the album's colour, slowly drifting, under a scrim. What
 * actually gets drawn depends on what the device can do — a field derived from the
 * palette on the top tier, the artwork itself blurred or softened below it.
 *
 * THREE TIERS, one API (spec §8):
 *  - 33+   AGSL RuntimeShader — a domain-warped flowing gradient built from the palette
 *  - 31-32 Modifier.blur over the art
 *  - 29-30 the art itself, magnified past the surface so resampling softens it (see
 *          [UPSCALE] for what that does and does not buy), under the same scrim
 *
 * `Modifier.blur` is API 31+ and **silently does nothing below it**; `RuntimeShader` is
 * API 33+. minSdk is 29, so neither may be required — hence the ladder. Tier selection
 * never leaks to callers: there is no tier parameter and no tier in the return type.
 *
 * The drift is identical on all three, so the MOTION reads the same on every device and
 * only the blur fidelity differs.
 */
@Composable
fun ArtBackdrop(
    art: SongArt?,
    palette: DominantColors,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val drift = rememberDrift(reducedMotion)

    Box(modifier.fillMaxSize().background(palette.bg)) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ShaderBackdrop(palette, drift, Modifier.fillMaxSize())

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                ArtImage(
                    art = art,
                    palette = palette,
                    initial = NO_GLYPH,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { applyDrift(drift.value) }
                        .blur(BLUR_RADIUS),
                )

            else ->
                // No RenderEffect anywhere in this branch, so it works down to API 29.
                ArtImage(
                    art = art,
                    palette = palette,
                    initial = NO_GLYPH,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            applyDrift(drift.value)
                            scaleX *= UPSCALE
                            scaleY *= UPSCALE
                        },
                )
        }

        // Shared scrim so text stays legible on any artwork, on every tier. Weighted to
        // the bottom because that is where the transport and the track title sit.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            palette.bg.copy(alpha = 0.35f),
                            palette.bg.copy(alpha = 0.80f),
                        )
                    )
                )
        )
    }
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
 * AGSL flowing gradient. Deliberately small in scope — two warped colour fields, not a
 * general shader framework. Colours come from the animated palette, so the backdrop
 * re-themes with everything else.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderBackdrop(
    palette: DominantColors,
    drift: State<Float>,
    modifier: Modifier,
) {
    val shader = remember { RuntimeShader(BACKDROP_AGSL) }

    Canvas(modifier) {
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uTime", drift.value * SHADER_TIME_SCALE)
        shader.setColorUniform("uBg", palette.bg.toArgb())
        shader.setColorUniform("uAccent", palette.accent.toArgb())
        shader.setColorUniform(
            "uWave",
            (palette.waveColors.firstOrNull() ?: palette.accent).toArgb(),
        )
        drawRect(brush = ShaderBrush(shader))
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
