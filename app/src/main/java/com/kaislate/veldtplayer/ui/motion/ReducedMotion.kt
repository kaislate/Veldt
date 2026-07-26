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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
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
 * Fade + 8dp rise, delayed by list position. Applied to browse list items so a
 * screen assembles itself instead of appearing all at once.
 */
fun Modifier.staggeredEntrance(index: Int, reduced: Boolean): Modifier = composed {
    var shown by remember { mutableStateOf(reduced) }
    LaunchedEffect(index, reduced) {
        if (!reduced) {
            delay(Motion.staggerDelayMs(index, reduced = false).toLong())
            shown = true
        } else {
            shown = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = Motion.gentle,
        label = "staggeredEntrance",
    )
    val riseDp = 8.dp
    this
        .alpha(progress)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val offsetPx = (riseDp.toPx() * (1f - progress)).toInt()
            layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, offsetPx)
            }
        }
}
