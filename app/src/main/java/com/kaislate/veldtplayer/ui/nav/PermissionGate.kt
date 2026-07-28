// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** READ_MEDIA_AUDIO on API 33+, READ_EXTERNAL_STORAGE on <=32 (spec §6.2). */
private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * The hosting [Activity], unwrapped from whatever `ContextWrapper` chain Compose hands down.
 *
 * `shouldShowRequestPermissionRationale` is an Activity method and there is no Context
 * overload, so this is not avoidable. Null-tolerant rather than a cast: a preview or a test
 * harness composes this tree with a non-Activity context, and the gate degrades to "keep
 * asking" there instead of crashing.
 */
private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * Owns both runtime permissions for the app and hands [content] the audio-access state.
 *
 * POST_NOTIFICATIONS matters even though playback works without it: on Android 13+ the
 * media notification is silently SUPPRESSED until it is granted, which is exactly the
 * bug fixed in commit 9a04d4d.
 *
 * **Denial has to be recoverable from inside the app, and it used not to be.** `granted` was
 * seeded once and only ever updated by the launcher callback, which left two dead ends:
 *
 * - **The dead button.** Two denials make Android mark the permission permanently denied, and
 *   from then on `launch()` resolves denied WITHOUT showing a dialog. "Grant access" then does
 *   nothing at all, forever, with no indication of why. [audioBlocked] is set from exactly that
 *   event — a request that came back denied while the platform also declines to show a
 *   rationale is the definition of "the dialog will never appear again" — and it re-points the
 *   one action at the app's settings page, which is the only place the decision can still be
 *   changed.
 * - **The stale wall.** Granting the permission in Settings does not restart the process, so a
 *   user who fixed it the hard way came back to the same wall and needed a force-stop. The
 *   [Lifecycle.Event.ON_RESUME] re-check below is the whole fix: the state is re-read every
 *   time the app comes forward, so returning from Settings — by ANY route, including the one
 *   this gate now offers — drops the wall on the frame the app resumes.
 *
 * One residual, deliberately not carrying a persisted flag to close: a *fresh* process that was
 * already permanently denied in an earlier session starts with [audioBlocked] false, because
 * "never asked" and "asked and permanently denied" are the same observable state before the
 * first request (`shouldShowRequestPermissionRationale` is false for both). The first press
 * therefore still spends itself on a request the system answers instantly — and the callback
 * flips the label to "Open settings" in the same breath. One wasted press that corrects itself,
 * rather than a button that is dead for good.
 */
@Composable
fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable (
        audioGranted: Boolean,
        audioBlocked: Boolean,
        requestAudio: () -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    // "The system will not show the dialog again." Set only from a request RESULT, never
    // guessed at composition: before the first request `shouldShowRequestPermissionRationale`
    // is false for a fresh install too, and seeding from it would send a first-run user to
    // Settings for a permission nobody has asked them about yet.
    var blocked by remember { mutableStateOf(false) }

    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        blocked = !ok && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, audioPermission)
        if (ok) onGranted()
    }

    LaunchedEffect(granted) { if (granted) onGranted() }

    // Re-read on every resume. Two things change the answer while this composition is alive and
    // neither goes through the launcher: the user toggling the permission in Settings, and the
    // system revoking it for an unused app. Both leave the process running, so without this the
    // gate would keep reporting whatever it last heard.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = ContextCompat.checkSelfPermission(context, audioPermission) ==
                    PackageManager.PERMISSION_GRANTED
                granted = now
                // Cleared on a grant so a later revoke starts from "ask", not from "Settings".
                if (now) blocked = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — this only un-suppresses the media notification */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    content(granted, blocked) {
        if (blocked) {
            // The app's own settings page — the only surface left that can still flip a
            // permanently denied permission. NEW_TASK because the context here can be an
            // application context in a preview or a test host; from a real Activity it is
            // redundant and harmless.
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } else {
            audioLauncher.launch(audioPermission)
        }
    }
}
