package com.kaislate.veldtplayer.ui.motion

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Reads the system animator duration scale; true when the user has animations off. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        Motion.reduced(scale)
    }
}

/**
 * Fade + [Motion.RISE_DP] rise, delayed by list position. Applied to browse list items so
 * a screen assembles itself instead of appearing all at once.
 *
 * Only the first [Motion.STAGGER_CAP] rows animate. A `LazyColumn` discards the
 * composition of rows scrolled out of view and re-composes them on the way back, so
 * animating unconditionally would restart the entrance for every row of a fling — a list
 * that reads as blank and lagging exactly when it should feel fast. Rows past the cap
 * already share one identical delay, so they lose nothing by appearing instantly.
 */
fun Modifier.staggeredEntrance(index: Int, reduced: Boolean): Modifier = composed {
    val animates = !reduced && index < Motion.STAGGER_CAP
    var shown by remember { mutableStateOf(!animates) }
    LaunchedEffect(index, reduced) {
        if (animates) delay(Motion.staggerDelayMs(index, reduced = false).toLong())
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.gentle,
        label = "staggeredEntrance",
    )
    this.graphicsLayer {
        alpha = progress
        translationY = Motion.RISE_DP.dp.toPx() * (1f - progress)
    }
}
