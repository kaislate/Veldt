// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Ported from Veldt Wisp (overlay/OverlayWindowManager.kt), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.*
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.graphics.Rect
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.pill.OverlayPermission
import com.kaislate.veldtplayer.pill.PillCommands
import com.kaislate.veldtplayer.pill.PillOverlay
import com.kaislate.veldtplayer.pill.ShowFailure
import com.kaislate.veldtplayer.pill.ui.island.IslandRoot
import com.kaislate.veldtplayer.pill.ui.island.PanelRoot
import com.kaislate.veldtplayer.pill.ui.island.PillAppearance
import com.kaislate.veldtplayer.pill.ui.island.pillAppearance
import com.kaislate.veldtplayer.pill.util.IslandPosition
import com.kaislate.veldtplayer.ui.theme.VeldtTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * Owns the pill's overlay window(s): attaches, expands/collapses and detaches them. Called on
 * the main thread by whatever drives show/hide (P1.5c Task 4's `PillController`, owned by
 * `PlaybackService` — hence [ServiceScoped]).
 *
 * Ported from Wisp with the `addView` failure handling unchanged. Veldt adaptations: Wisp's
 * API 33/34 single-window branch is gone (see [setExpanded]); settings come from Veldt's
 * `pill_*` keys via [PillAppearance];
 * the overlay-permission check goes through the [OverlayPermission] seam; transport commands
 * go through [PillCommands] (the bus is one-way in Veldt); the stash flick is reported through
 * [onStashRequested] instead of an intent to Wisp's own foreground service; each window's
 * content is wrapped in [VeldtTheme] so it follows the user's Light/Dark choice; and a failed
 * attach is published on [showFailure] so the stand-down can be surfaced.
 */
@ServiceScoped
class OverlayWindowManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val overlayPermission: OverlayPermission,
    private val commands: PillCommands,
) : PillOverlay {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var showing = false

    /**
     * Called when the user flicks the collapsed pill toward its edge (Wisp's "stash"). Wisp
     * sent `ACTION_STASH` to its foreground service, which told the state machine; Veldt has no
     * such service, so the owner of this manager wires it (Task 4). Unset, the flick does
     * nothing.
     */
    override var onStashRequested: (() -> Unit)? = null

    private val _showFailure = MutableStateFlow<ShowFailure?>(null)

    /**
     * Why the last [showIsland] could not attach the pill, or null after a successful attach.
     * The pill "stands down" on these (spec §3) rather than crashing or retrying in a loop;
     * this is what lets the stand-down be shown to the user.
     */
    override val showFailure: StateFlow<ShowFailure?> = _showFailure.asStateFlow()

    // Expanded-panel state lives here so the outside-touch listener can collapse it.
    private val expandedState = androidx.compose.runtime.mutableStateOf(false)

    // Expansion uses a SECOND window: the pill window never
    // resizes (window resizes can't be frame-synced with content and always lurch
    // sideways), and the morph plays inside a separate fixed panel-sized window
    // that exists only while expanded — so there's never a dead-zone either.
    private var bigView: ComposeView? = null
    private var bigOwner: OverlayOwner? = null

    // Separate-entity animation states: the pill fades out
    // while the panel fades/scales in as its own surface, and vice versa.
    private val pillHiddenState = androidx.compose.runtime.mutableStateOf(false)
    private val panelVisibleState = androidx.compose.runtime.mutableStateOf(false)

    // Latest placement, cached so the panel window can be created to match.
    @Volatile private var currentPosition: IslandPosition = IslandPosition.TOP_CENTER
    @Volatile private var currentOffsetDp: Int = PillAppearance.TOP_OFFSET_DP

    private companion object {
        const val WINDOW_HEIGHT_DP = 320
    }

    // Every API level uses the same two windows: a content-sized pill window, and a
    // panel window that exists only while expanded. Wisp kept ONE fixed panel-sized
    // window on API 33/34 and let AttachedSurfaceControl.setTouchableRegion pass
    // touches through its transparent parts — but Android 12+ judges untrusted-overlay
    // occlusion on the window's FRAME, not its touchable region, so every tap under
    // that invisible rectangle was dropped ("Untrusted touch due to occlusion …
    // obscuring opacity = 1.00, maximum allowed = 0.80"; measured on an API 33 S20 FE,
    // 2026-09-27). A window sized to what it draws is Google's documented pattern for
    // overlay touch pass-through.
    //
    // The morph plays in the panel window; the pill window is simply hidden meanwhile.
    // Neither window ever resizes, so nothing can lurch.
    private fun setExpanded(value: Boolean) {
        if (value) openPanelWindow() else closePanelWindow()
    }

    private fun panelLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(windowWidthDp()),
            dpToPx(WINDOW_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = currentPosition.gravity
            y = dpToPx(currentOffsetDp)
            dimAmount = 0.35f
            // No system enter/exit animation — the panel must appear pixel-matched
            // with the pill it replaces, not fade in over it (that read as a blink).
            windowAnimations = android.R.style.Animation
            @Suppress("DEPRECATION")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    /**
     * The settings every window's content reads, and Veldt's theme around it. An overlay
     * window has no Activity, so nothing else would provide `LocalIsLightTheme` or the
     * typography; [VeldtTheme] is the one place the Light/Dark/System choice is resolved.
     */
    @Composable
    private fun OverlayContent(content: @Composable (PillAppearance) -> Unit) {
        val themeMode by settingsRepo.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
        val appearanceFlow = remember { settingsRepo.pillAppearance() }
        val appearance by appearanceFlow.collectAsState(initial = PillAppearance.DEFAULT)
        VeldtTheme(themeMode) { content(appearance) }
    }

    private fun openPanelWindow() {
        if (bigView != null) return
        val compose = ComposeView(context).also { cv ->
            val o = OverlayOwner()
            cv.setViewTreeLifecycleOwner(o)
            cv.setViewTreeSavedStateRegistryOwner(o)
            cv.setViewTreeViewModelStoreOwner(o)
            bigOwner = o
            cv.setContent {
                OverlayContent { appearance ->
                    val panelVisible by panelVisibleState
                    PanelRoot(
                        visible = panelVisible,
                        onCollapse = { setExpanded(false) },
                        commands = commands,
                        vibrant = appearance.vibrant,
                        position = appearance.position,
                        thumbShape = appearance.thumbShape,
                        waveColorMode = appearance.waveColorMode,
                        panelWidthDp = appearance.panelWidthDp,
                        crossfadeMs = appearance.crossfadeMs,
                        waveStyle = appearance.waveStyle,
                        consume = appearance.consume
                    )
                }
            }
            // Panel window is touch-modal: any tap outside its bounds collapses.
            cv.setOnTouchListener { view2, ev ->
                val outside = ev.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                    (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                        (ev.x < 0f || ev.y < 0f ||
                            ev.x > view2.width || ev.y > view2.height))
                if (outside && expandedState.value) {
                    setExpanded(false)
                    true
                } else false
            }
        }
        try {
            wm.addView(compose, panelLayoutParams())
            bigOwner?.onStart()
            bigOwner?.onResume()
            bigView = compose
            expandedState.value = true
            // Separate entities: the pill fades out (in its own window) while the
            // panel fades/scales in (in this one). No cross-window impersonation.
            pillHiddenState.value = true
            compose.post { panelVisibleState.value = true }
        } catch (e: Exception) {
            Log.w("Overlay", "Cannot add panel window: ${e.message}")
            bigOwner?.onDestroy(); bigOwner = null; bigView = null
        }
    }

    private fun closePanelWindow() {
        val v = bigView ?: run {
            expandedState.value = false
            pillHiddenState.value = false
            return
        }
        expandedState.value = false
        panelVisibleState.value = false // panel fades/scales out...
        pillHiddenState.value = false   // ...while the pill fades back in
        v.postDelayed({
            if (!expandedState.value) removePanelWindow()
        }, 260)
    }

    private fun removePanelWindow() {
        runCatching { bigOwner?.onPause(); bigOwner?.onStop() }
        runCatching { bigView?.let { wm.removeViewImmediate(it) } }
        runCatching { bigOwner?.onDestroy() }
        bigOwner = null
        bigView = null
        panelVisibleState.value = false
        pillHiddenState.value = false
    }

    // Latest panel-width setting (collected in setContent); window width follows it.
    @Volatile private var panelWidthDpSetting: Int = PillAppearance.PANEL_WIDTH_DP

    private fun windowWidthDp(): Int = panelWidthDpSetting + 24

    // The panel window reads it when it opens; the pill window sizes itself to its content.
    private fun applyPanelWidth(dp: Int) {
        panelWidthDpSetting = dp
    }

    /**
     * Keeps the system-gesture exclusion glued to the pill's current bounds, including
     * mid-animation.
     */
    private fun updateIslandBounds(bounds: androidx.compose.ui.geometry.Rect) {
        val v = root ?: return
        v.systemGestureExclusionRects = listOf(
            Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        )
    }

    /**
     * Hands the whole of [target]'s rectangle to the system as a gesture-exclusion
     * area, so the pill keeps the touches that land on it.
     *
     * A pill parked against an edge sits in the strip the system reserves for its
     * own swipes — the shade pull-down at the top, back/home below — and an overlay
     * loses that contest by default: the gesture fires and the pill never sees the
     * finger. Publishing the bounds as an exclusion rect reverses the priority.
     *
     * Deferred through [View.post] because the rect is expressed in the view's own
     * coordinates, and both dimensions read zero until a layout pass has sized it.
     *
     * One-shot, for the moment the pill window is attached; [updateIslandBounds] is
     * what keeps the exclusion following the content once it starts animating.
     */
    private fun applySystemGestureExclusion(target: View) {
        target.post {
            target.systemGestureExclusionRects =
                listOf(Rect(0, 0, target.width, target.height))
        }
    }

    /**
     * Density-independent pixels to real ones.
     *
     * Every dimension in this class is authored in dp, while [WindowManager] accepts
     * nothing but device pixels, so each one crosses this on its way out. Truncating
     * rather than rounding: sub-pixel window geometry is meaningless, and biasing
     * consistently downwards keeps a window from creeping past a screen edge.
     *
     * The metrics are re-read on every call instead of being cached — density shifts
     * under a running process on a fold, a display swap or a display-size change, and
     * a stale factor would lay the window out for the screen it used to be on.
     */
    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    private fun applyPlacement(pos: IslandPosition, offsetDp: Int) {
        currentPosition = pos
        currentOffsetDp = offsetDp
        val v = root ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        val y = dpToPx(offsetDp)
        if (lp.gravity != pos.gravity || lp.y != y) {
            lp.gravity = pos.gravity
            lp.y = y // y is distance from the anchored edge for TOP/BOTTOM gravity
            runCatching { wm.updateViewLayout(v, lp) }
        }
    }

    /**
     * Layout params for the pill's own window, sized to its content: any transparent
     * margin would be a dead zone that eats taps meant for the app underneath (see
     * [setExpanded] for why a touchable region cannot fix that).
     */
    private fun pillLayoutParams(): WindowManager.LayoutParams {
        val wrap = WindowManager.LayoutParams.WRAP_CONTENT
        return WindowManager.LayoutParams(
            wrap,
            wrap,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE       // never steal focus / close the IME
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL   // taps outside go to what's below
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH // ...but still notify us, so we can collapse
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS),
            PixelFormat.TRANSLUCENT
        ).apply {
            // Initial placement only: applyPlacement() overwrites gravity and y
            // from the user's settings as soon as the content composes.
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dpToPx(4) // clear of the status-bar content without a visible gap
            @Suppress("DEPRECATION")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /**
     * Builds the pill's [ComposeView] and records it (with its owner) in the
     * fields. Split out of [showIsland] so the add/rollback path there stays
     * readable; call it only when [root] is null.
     */
    private fun createPillView(): ComposeView =
        ComposeView(context).also { cv ->
            // A ComposeView living outside an Activity brings no owners of its
            // own. All three have to be attached before setContent or the first
            // composition throws — attaching them afterwards is too late.
            val o = OverlayOwner()
            cv.setViewTreeLifecycleOwner(o)
            cv.setViewTreeSavedStateRegistryOwner(o)
            cv.setViewTreeViewModelStoreOwner(o)
            owner = o

            cv.setContent {
                OverlayContent { appearance ->
                    val pillHidden by pillHiddenState

                    val position = appearance.position
                    LaunchedEffect(position, appearance.topOffsetDp) {
                        applyPlacement(position, appearance.topOffsetDp)
                    }
                    LaunchedEffect(appearance.panelWidthDp) { applyPanelWidth(appearance.panelWidthDp) }

                    // The panel window takes over the pill's pixels while expanded,
                    // so the pill dissolves rather than vanishing.
                    val pillAlpha = animateFloatAsState(
                        targetValue = if (pillHidden) 0f else 1f,
                        animationSpec = tween(150),
                        label = "pill-dissolve"
                    )

                    Box(modifier = Modifier.graphicsLayer { alpha = pillAlpha.value }) {
                        IslandRoot(
                            // The panel is a separate window; this one only ever
                            // draws the collapsed pill.
                            expanded = false,
                            onExpand = { setExpanded(true) },
                            onCollapse = { setExpanded(false) },
                            onBoundsChanged = { updateIslandBounds(it) },
                            onStashSwipe = { onStashRequested?.invoke() },
                            commands = commands,
                            vibrant = appearance.vibrant,
                            position = position,
                            thumbShape = appearance.thumbShape,
                            waveColorMode = appearance.waveColorMode,
                            pillTextWidthDp = appearance.pillTextWidthDp,
                            panelWidthDp = appearance.panelWidthDp,
                            crossfadeMs = appearance.crossfadeMs,
                            showPillControls = appearance.showPillControls,
                            pillControlSet = appearance.pillControlSet,
                            pillControlPosition = appearance.pillControlPosition,
                            waveStyle = appearance.waveStyle,
                            consume = appearance.consume
                        )
                    }
                }
            }

            cv.setOnTouchListener { v, ev ->
                // Collapsed, every event belongs to Compose — bail out before
                // looking at coordinates.
                if (!expandedState.value) return@setOnTouchListener false
                // While the panel window is open, a tap beyond this one may arrive
                // either as ACTION_OUTSIDE or as an ordinary DOWN carrying
                // out-of-range coordinates.
                val outside = ev.actionMasked == MotionEvent.ACTION_OUTSIDE ||
                    (ev.actionMasked == MotionEvent.ACTION_DOWN &&
                        (ev.x < 0f || ev.y < 0f || ev.x > v.width || ev.y > v.height))
                if (outside) {
                    setExpanded(false)
                    true
                } else false
            }

            root = cv
        }

    /** Called by the pill's driver on the main thread. */
    fun showIsland() {
        if (showing) {
            Log.d("Overlay", "showIsland: pill window already attached, nothing to do")
            return
        }
        // The island always comes back as a pill, never mid-expansion.
        expandedState.value = false
        removePanelWindow()

        // "Display over other apps" can be revoked at any moment; addView would
        // then throw a SecurityException from deep inside the window manager.
        if (!overlayPermission.isGranted()) {
            Log.d("Overlay", "showIsland: overlay permission not held, staying hidden")
            // Deliberately NOT a ShowFailure: Android refused nothing — the permission is
            // simply off, which Settings already reports with its grant row (and the
            // controller as PERMISSION_LOST). Publishing it here would make Settings say
            // "Android refused…" and point the user away from the fix.
            return
        }
        Log.d("Overlay", "showIsland: attaching the pill window")

        // Reuse an existing ComposeView: a rebuilt one loses everything it
        // remembered, and the entrance animation would replay on every re-show.
        val view = root ?: createPillView()

        try {
            wm.addView(view, pillLayoutParams())
            owner?.onStart()
            owner?.onResume()
            showing = true
            _showFailure.value = null
            // Claim the pill's area back from the shade pull-down gesture.
            applySystemGestureExclusion(view)
        } catch (e: SecurityException) {
            Log.w("Overlay", "pill window rejected — permission revoked or OEM overlay policy: ${e.message}")
            _showFailure.value = ShowFailure("permission revoked or blocked by the system: ${e.message}")
            cleanup()
        } catch (e: WindowManager.BadTokenException) {
            Log.w("Overlay", "pill window rejected — bad window token: ${e.message}")
            _showFailure.value = ShowFailure("window rejected (bad token): ${e.message}")
            cleanup()
        } catch (e: IllegalStateException) {
            Log.w("Overlay", "pill window rejected — illegal state, view may already be attached: ${e.message}")
            _showFailure.value = ShowFailure("window rejected (illegal state): ${e.message}")
            cleanup()
        }
    }

    /**
     * Rolls back to a state a later [showIsland] can start from cleanly after a
     * failed add: no panel window, no owner, no view, not showing.
     */
    private fun cleanup() {
        removePanelWindow()
        // Usually a no-op — the add is what failed, so there is nothing attached
        // and this throws harmlessly. It is here for the case where the add
        // succeeded and a later step in showIsland() threw: dropping the field
        // without detaching would strand a live window nobody can dismiss.
        runCatching { root?.let { wm.removeViewImmediate(it) } }
        owner?.onDestroy()
        owner = null
        root = null
        showing = false
    }

    override fun hide() {
        if (!showing) {
            Log.d("Overlay", "hide: no pill window attached, nothing to do")
            return
        }
        Log.d("Overlay", "hide: detaching the pill window")

        // The panel window's dismissal is driven by the expansion state this
        // window owns — leave it up and nothing can take it down by touch.
        expandedState.value = false
        removePanelWindow()

        val view = root
        // Each step is guarded on its own: removing a view that never made it
        // onto the window manager throws, and that must not stop the rest from
        // running or we would keep believing the pill is still showing.

        // Compose watches the lifecycle; step it down while the view is still
        // attached so effects are wound up rather than abandoned mid-flight.
        runCatching { owner?.onPause(); owner?.onStop() }
        // Immediate, not deferred: a deferred removal keeps the pixels on screen
        // until the next traversal, which reads as the pill lingering on after
        // playback has already stopped.
        runCatching { view?.let { wm.removeViewImmediate(it) } }
        runCatching { owner?.onDestroy() } // also clears the view-model store
        // Both fields hold a live composition; keeping them leaks the whole tree.
        owner = null
        root = null
        showing = false
    }
}
