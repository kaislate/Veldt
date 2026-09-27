// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (ui/island/IslandOverlay.kt), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill.ui.island

import android.graphics.Bitmap
import android.media.MediaMetadata
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import com.kaislate.veldtplayer.pill.PillCommands
import com.kaislate.veldtplayer.pill.util.IslandPosition
import com.kaislate.veldtplayer.ui.components.HillsWave
import com.kaislate.veldtplayer.ui.motion.Motion
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.ColorExtractor
import com.kaislate.veldtplayer.ui.theme.LocalIsLightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [ArtSeed] for [albumArt], derived off the main thread ([ColorExtractor.seedOf] walks
 * every pixel) and [ArtSeed.NEUTRAL] until it lands — so the first frame is already opaque and
 * legible. Replaces Wisp's `ColorExtractor.extract` in a `LaunchedEffect`.
 */
@Composable
internal fun rememberArtSeed(albumArt: Bitmap?): ArtSeed {
    var seed by remember { mutableStateOf(ArtSeed.NEUTRAL) }
    LaunchedEffect(albumArt) {
        seed = withContext(Dispatchers.Default) { ColorExtractor.seedOf(albumArt) }
    }
    return seed
}

/** A solved colour drifting to its new value on a track change, like the rest of Veldt (spec §6). */
@Composable
internal fun animatedRole(target: Color, label: String): Color =
    animateColorAsState(target, Motion.palette, label = label).value

/**
 * Content of the dedicated PANEL window used on devices without a usable
 * touchable-region API (Android 10–12, 15+). The panel is its own entity: it
 * fades/scales in from the pill's anchor and out again — no attempt to
 * impersonate the pill (two windows can't be frame-locked, so entity-morph
 * attempts always leaked blinks).
 */
@Composable
fun PanelRoot(
    visible: Boolean,
    onCollapse: () -> Unit,
    commands: PillCommands,
    vibrant: Boolean = false,
    position: IslandPosition = IslandPosition.TOP_CENTER,
    thumbShape: String = "circle",
    waveColorMode: String = "auto",
    panelWidthDp: Int = 400,
    crossfadeMs: Int = 450,
    waveStyle: String = "hills",
    consume: Boolean = false
) {
    val morphOrigin = TransformOrigin(position.horizontalBias, if (position.isBottom) 1f else 0f)
    Box(Modifier.fillMaxSize(), contentAlignment = position.alignment) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(180)) + scaleIn(
                spring(dampingRatio = 0.85f, stiffness = 380f),
                initialScale = 0.85f,
                transformOrigin = morphOrigin
            ),
            exit = fadeOut(tween(160)) + scaleOut(
                tween(200), targetScale = 0.85f,
                transformOrigin = morphOrigin
            )
        ) {
            val edgePadding =
                if (position.isBottom) Modifier.padding(bottom = 4.dp)
                else Modifier.padding(top = 4.dp)
            Box(Modifier.width(panelWidthDp.dp).then(edgePadding)) {
                MusicPopUp(
                    onSwipeUpClose = onCollapse,
                    commands = commands,
                    vibrant = vibrant,
                    thumbShape = thumbShape,
                    waveColorMode = waveColorMode,
                    crossfadeMs = crossfadeMs,
                    waveStyle = waveStyle,
                    consume = consume
                )
            }
        }
    }
}

/**
 * Root of the overlay window: morphs between the pill and the expanded media
 * panel IN PLACE (anchored top-center), so the pill visually expands into the
 * panel instead of launching a separate activity (whose new-task animation the
 * window manager animates from the bottom and apps cannot override).
 */
@Composable
fun IslandRoot(
    expanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onBoundsChanged: (androidx.compose.ui.geometry.Rect) -> Unit,
    commands: PillCommands,
    onStashSwipe: () -> Unit = {},
    vibrant: Boolean = false,
    position: IslandPosition = IslandPosition.TOP_CENTER,
    thumbShape: String = "circle",
    waveColorMode: String = "auto",
    pillTextWidthDp: Int = 128,
    panelWidthDp: Int = 400,
    crossfadeMs: Int = 450,
    showPillControls: Boolean = false,
    pillControlSet: String = "play-next",
    pillControlPosition: String = "right",
    waveStyle: String = "hills",
    consume: Boolean = false
) {
    // The host window is WRAP_CONTENT, so the root Box must NOT fill it — any
    // transparent margin in an overlay window is a dead zone that eats taps meant
    // for the app beneath (see OverlayWindowManager.setExpanded).
    val springSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 380f)
    Box(
        modifier = Modifier
            // While expanded, a tap on the window's transparent margin collapses
            // the panel (taps fully outside the window arrive as ACTION_OUTSIDE).
            .pointerInput(expanded) {
                if (expanded) {
                    // The no-op long-press is load-bearing: supplying it makes
                    // detectTapGestures arm its long-press timeout, so holding the margin
                    // and releasing does NOT also collapse the panel.
                    detectTapGestures(onLongPress = {}, onTap = { onCollapse() })
                }
            },
        contentAlignment = position.alignment
    ) {
        Box(
            Modifier.onGloballyPositioned { onBoundsChanged(it.boundsInWindow()) }
        ) {
            val morphOrigin = TransformOrigin(position.horizontalBias, if (position.isBottom) 1f else 0f)
            AnimatedContent(
                targetState = expanded,
                contentAlignment = position.alignment,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(
                        springSpec, initialScale = 0.8f,
                        transformOrigin = morphOrigin
                    )).togetherWith(
                        fadeOut(tween(140)) + scaleOut(
                            springSpec, targetScale = 0.8f,
                            transformOrigin = morphOrigin
                        )
                    ).using(SizeTransform(clip = false) { _, _ ->
                        spring(dampingRatio = 0.9f, stiffness = 380f)
                    })
                },
                label = "island-expand"
            ) { isExpanded ->
                if (isExpanded) {
                    val edgePadding =
                        if (position.isBottom) Modifier.padding(bottom = 4.dp)
                        else Modifier.padding(top = 4.dp)
                    Box(Modifier.width(panelWidthDp.dp).then(edgePadding)) {
                        MusicPopUp(
                            onSwipeUpClose = onCollapse,
                            commands = commands,
                            vibrant = vibrant,
                            thumbShape = thumbShape,
                            waveColorMode = waveColorMode,
                            crossfadeMs = crossfadeMs,
                            waveStyle = waveStyle,
                            consume = consume
                        )
                    }
                } else {
                    IslandOverlay(
                        onShortTap = onExpand,
                        onLongPress = { },
                        commands = commands,
                        onStashSwipe = onStashSwipe,
                        stashDirectionUp = !position.isBottom,
                        vibrant = vibrant,
                        waveColorMode = waveColorMode,
                        textWidthDp = pillTextWidthDp,
                        crossfadeMs = crossfadeMs,
                        showPillControls = showPillControls,
                        pillControlSet = pillControlSet,
                        pillControlPosition = pillControlPosition,
                        waveStyle = waveStyle
                    )
                }
            }
        }
    }
}

/**
 * The pill's touch surface. It draws nothing of its own — [PillContent] is the pill — and
 * exists only to own the two gestures the collapsed pill answers to.
 *
 * The whole strip is one target. There is no ripple: a translucent pill floating over
 * somebody else's app has no surface for a ripple to belong to, and with the entire pill
 * being the button there is nothing for the ripple to disambiguate. Both handlers fire a
 * haptic first, so the pill confirms the touch even where the visual response is a window
 * animation that takes a frame or two to start.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IslandOverlay(
    onShortTap: () -> Unit,
    onLongPress: () -> Unit,
    commands: PillCommands,
    onStashSwipe: () -> Unit = {},
    stashDirectionUp: Boolean = true,
    leftMarginDp: Int = 2,
    rightMarginDp: Int = 2,
    vibrant: Boolean = false,
    waveColorMode: String = "auto",
    textWidthDp: Int = 128,
    crossfadeMs: Int = 450,
    showPillControls: Boolean = false,
    pillControlSet: String = "play-next",
    pillControlPosition: String = "right",
    waveStyle: String = "hills",
) {
    val view = LocalView.current
    // Unused on purpose: combinedClickable insists on an interaction source, and this one
    // exists so that nothing observes it and no indication is ever drawn.
    val interactions = remember { MutableInteractionSource() }

    PillContent(
        commands = commands,
        vibrant = vibrant,
        waveColorMode = waveColorMode,
        textWidthDp = textWidthDp,
        leftMarginDp = leftMarginDp,
        rightMarginDp = rightMarginDp,
        crossfadeMs = crossfadeMs,
        showControls = showPillControls,
        controlSet = pillControlSet,
        controlPosition = pillControlPosition,
        waveStyle = waveStyle,
        modifier = Modifier
            .combinedClickable(
                interactionSource = interactions,
                indication = null,
                role = Role.Button,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onShortTap()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onLongPress()
                },
            )
            // Keyed on the direction so the recogniser re-arms when the user moves the pill
            // between a top and a bottom anchor; the threshold is per-event, so a flick
            // stashes and a slow deliberate drag does not. Neither gesture consumes the
            // event — arbitration between the tap and the drag is the framework's job.
            .pointerInput(stashDirectionUp) {
                detectVerticalDragGestures { _, dragAmount ->
                    if (PillLayout.isStashDrag(dragAmount, stashDirectionUp)) onStashSwipe()
                }
            },
    )
}

/**
 * The pill itself: the artwork, the title and artist, the wave along its bottom edge, and —
 * when the user has asked for them — the transport buttons.
 *
 * Its size is entirely content-driven. The overlay window is gravity-anchored by
 * `OverlayWindowManager`, so a pill that grows wider re-centres itself and one that grows
 * taller extends from whichever edge it is anchored to. The absence of any centring or
 * offset logic here is deliberate, not an omission.
 *
 * Veldt: colours are the solved [PillColors.pill] roles on an OPAQUE surface (Wisp drew the
 * surface at 0.96→0.90 alpha, which would let an unknown app's pixels into the ground the text
 * was solved against), and the artist line is the solved secondary tone rather than the title
 * colour at 0.85 alpha. Wisp's premium whole-pill effects are not ported (spec §1.3, deferred).
 */
@Composable
fun PillContent(
    commands: PillCommands,
    vibrant: Boolean = false,
    waveColorMode: String = "auto",
    textWidthDp: Int = 128,
    leftMarginDp: Int = 2,
    rightMarginDp: Int = 2,
    crossfadeMs: Int = 450,
    showControls: Boolean = false,
    controlSet: String = "play-next",
    controlPosition: String = "right",
    waveStyle: String = "hills",
    modifier: Modifier = Modifier,
) {
    val albumArt by MediaSessionBus.albumArt.collectAsState()
    val playbackState by MediaSessionBus.playbackState.collectAsState()
    val metadata by MediaSessionBus.metadata.collectAsState()

    val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
    val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
    val isPlaying = PillLayout.isPlaying(playbackState)

    val isLight = LocalIsLightTheme.current
    val seed = rememberArtSeed(albumArt)
    val roles = PillColors.pill(seed, isLight, waveColorMode)

    val surface = animatedRole(roles.surface, "pill-surface")
    val titleColor = animatedRole(roles.title, "pill-title")
    val subtitleColor = animatedRole(roles.subtitle, "pill-subtitle")
    val controlsColor = animatedRole(roles.controls, "pill-controls")
    val waveColor = animatedRole(roles.wave, "pill-wave")

    // The wave, not the text, carries the paused state: it settles and fades. A state mark,
    // not a legibility role, so this alpha is the signal rather than a dimmed tone.
    val waveAlpha by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0.3f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "pill-wave-fade",
    )

    val artAndText: @Composable () -> Unit = {
        CrossfadeArt(
            bitmap = albumArt,
            durationMs = crossfadeMs,
            placeholderInitial = title.firstOrNull() ?: ' ',
            placeholderPalette = seed.colors(isLight),
            modifier = Modifier.size(30.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(
            modifier = Modifier.widthIn(max = textWidthDp.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The marquee is unconditional. Compose only animates one that actually
            // overflows, so a short title sits still without being asked to.
            Text(
                text = title,
                color = titleColor,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
            Text(
                text = artist,
                color = subtitleColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
            )
        }
    }

    val transport: @Composable () -> Unit = {
        PillControls(controlSet = controlSet, isPlaying = isPlaying, tint = controlsColor, commands = commands)
    }

    val rowPad = Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)

    Box(
        modifier
            .width(IntrinsicSize.Max)
            .shadow(8.dp, RoundedCornerShape(24.dp), clip = false)
            .clip(RoundedCornerShape(24.dp))
            .background(surface)
    ) {
        HillsWave(
            isPlaying = isPlaying,
            color = waveColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(14.dp)
                .alpha(waveAlpha),
            vibrant = vibrant,
            waveColors = emptyList(),
            waveStyle = waveStyle,
            isLight = isLight,
        )

        when (PillLayout.arrangementFor(showControls, controlPosition)) {
            PillArrangement.TEXT_ONLY ->
                Row(rowPad, verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(leftMarginDp.dp))
                    artAndText()
                    Spacer(Modifier.width(rightMarginDp.dp))
                }

            PillArrangement.CONTROLS_BELOW ->
                Column(
                    Modifier.padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(leftMarginDp.dp))
                        artAndText()
                        Spacer(Modifier.width(rightMarginDp.dp))
                    }
                    transport()
                }

            PillArrangement.CONTROLS_LEFT ->
                Row(rowPad, verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(leftMarginDp.dp))
                    transport()
                    Spacer(Modifier.width(4.dp))
                    artAndText()
                    Spacer(Modifier.width(rightMarginDp.dp))
                }

            PillArrangement.CONTROLS_RIGHT ->
                Row(rowPad, verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(leftMarginDp.dp))
                    artAndText()
                    Spacer(Modifier.width(4.dp))
                    transport()
                    Spacer(Modifier.width(rightMarginDp.dp))
                }
        }
    }
}

/**
 * Always-on transport controls drawn on the pill. Each button owns its click, so
 * tapping a control fires playback (not the pill's tap-to-expand or swipe-to-stash).
 */
@Composable
private fun PillControls(controlSet: String, isPlaying: Boolean, tint: Color, commands: PillCommands) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Which buttons, and in what order, is a value question — it lives in PillLayout
        // where it is assertable without a device; what each one sends lives in PillTransport.
        PillLayout.buttonsFor(controlSet).forEach { button ->
            val (icon, desc) = when (button) {
                PillButton.PREVIOUS -> Icons.Filled.SkipPrevious to "Previous"
                PillButton.PLAY_PAUSE ->
                    if (isPlaying) Icons.Filled.Pause to "Pause" else Icons.Filled.PlayArrow to "Play"
                PillButton.NEXT -> Icons.Filled.SkipNext to "Next"
            }
            PillControlButton(icon, desc, tint) { PillTransport.press(button, commands) }
        }
    }
}

@Composable
private fun PillControlButton(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(20.dp))
    }
}
