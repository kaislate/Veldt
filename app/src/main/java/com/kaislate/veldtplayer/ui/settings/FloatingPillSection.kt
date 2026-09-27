// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.pill.PackageManagerWispPresence
import com.kaislate.veldtplayer.pill.PillStandDown
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel

/** Where "Get Veldt Wisp" sends the user: the newest release, whatever its version. */
internal const val WISP_RELEASES_URL = "https://github.com/kaislate/veldt-wisp/releases/latest"

/**
 * The three places the "Floating pill" section sends the user, built here so that what is sent is
 * a plain value a test can read, and only the sending goes through [FloatingPillSection]'s
 * `startActivity` seam.
 */
internal object PillIntents {
    /**
     * The Releases page, in whatever browser the user has. A user-started `ACTION_VIEW`, so it is
     * the browser that goes online, not Veldt; see `OfflineByDefaultAuditTest`'s KDoc.
     */
    fun getWisp(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(WISP_RELEASES_URL)).addCategory(Intent.CATEGORY_BROWSABLE)

    /**
     * Wisp's own launcher entry, or null if it has none (or went away since the section last
     * looked). Visible to Veldt at all only because of the manifest's `<queries>` entry.
     */
    fun openWisp(packageManager: PackageManager): Intent? =
        packageManager.getLaunchIntentForPackage(PackageManagerWispPresence.WISP_PACKAGE)

    /** The system's "Display over other apps" page for Veldt itself. */
    fun grantOverlay(packageName: String): Intent =
        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
}

/**
 * Starts [intent] from [context], answering whether anything took it. A phone with no browser
 * (or a work profile that blocks one) throws [ActivityNotFoundException] for "Get Veldt Wisp";
 * that is a thing to tell the user, not a crash.
 */
internal fun startActivitySafely(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** Everything the "Floating pill" section shows depends on, as one value. */
internal data class FloatingPillState(
    val enabled: Boolean,
    val wispInstalled: Boolean,
    val forceBuiltIn: Boolean,
    val overlayGranted: Boolean,
    val standDown: PillStandDown = PillStandDown.NONE,
)

/** [FloatingPillSection], fed from [vm]. Wisp's presence is a live flow, so this follows it. */
@Composable
internal fun FloatingPillSection(
    vm: SettingsViewModel,
    onOpenAppearance: () -> Unit,
    startActivity: (Intent) -> Boolean,
) {
    val enabled by vm.pillEnabled.collectAsStateWithLifecycle()
    val wispInstalled by vm.wispInstalled.collectAsStateWithLifecycle()
    val forceBuiltIn by vm.pillForceBuiltIn.collectAsStateWithLifecycle()
    val overlayGranted by vm.overlayPermissionGranted.collectAsStateWithLifecycle()
    val standDown by vm.pillStandDown.collectAsStateWithLifecycle()
    FloatingPillSection(
        state = FloatingPillState(enabled, wispInstalled, forceBuiltIn, overlayGranted, standDown),
        onEnabledChange = vm::setPillEnabled,
        onForceBuiltInChange = vm::setPillForceBuiltIn,
        onOpenAppearance = onOpenAppearance,
        startActivity = startActivity,
    )
}

/**
 * The Settings "Floating pill" section (Step 5 spec §9): one switch, and below it only what
 * applies to the pill the user will actually see.
 *
 * - Off: nothing else. There is no pill to configure.
 * - On, no Wisp: Veldt draws the pill, so its permission, its status and its appearance are
 *   here, plus the way to get Wisp for the pill in every music app.
 * - On, Wisp installed: Wisp draws it, so Veldt's own permission and appearance would be
 *   settings for a pill that never shows. They come back only if the user picks Veldt's own
 *   pill instead, along with the one thing that choice needs from them in Wisp.
 *
 * Rows that have nothing to ask are absent, not greyed: the permission row shows only while the
 * permission is missing, and the stand-down line only while there is one.
 *
 * [startActivity] is the seam for every intent the section fires; it answers false when nothing
 * could take the intent, and the section says so in a toast.
 */
@Composable
internal fun FloatingPillSection(
    state: FloatingPillState,
    onEnabledChange: (Boolean) -> Unit,
    onForceBuiltInChange: (Boolean) -> Unit,
    onOpenAppearance: () -> Unit,
    startActivity: (Intent) -> Boolean,
) {
    val context = LocalContext.current
    val wispShowing = state.enabled && state.wispInstalled && !state.forceBuiltIn

    SectionLabel("Floating pill")
    SwitchRow(
        label = "Floating pill",
        explanation = if (wispShowing) {
            "Veldt Wisp is showing the pill"
        } else {
            "A now-playing pill when you leave Veldt"
        },
        checked = state.enabled,
        onCheckedChange = onEnabledChange,
    )
    if (!state.enabled) return

    if (state.wispInstalled) {
        SettingsLinkRow(
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            label = "Open Veldt Wisp",
            onClick = {
                val opened = PillIntents.openWisp(context.packageManager)?.let(startActivity) ?: false
                if (!opened) toast(context, "Veldt Wisp couldn't be opened.")
            },
        )
        SwitchRow(
            label = "Use Veldt's own pill instead",
            checked = state.forceBuiltIn,
            onCheckedChange = onForceBuiltInChange,
        )
        if (state.forceBuiltIn) {
            Note("Turn off Veldt Wisp's pill for Veldt so you don't see two.")
            OwnPillRows(state, onOpenAppearance, startActivity)
        }
    } else {
        OwnPillRows(state, onOpenAppearance, startActivity)
        SettingsLinkRow(
            icon = Icons.Filled.Download,
            label = "Get Veldt Wisp",
            caption = "The standalone pill, for every music app",
            onClick = {
                if (!startActivity(PillIntents.getWisp())) {
                    toast(context, "No app can open links. The page is $WISP_RELEASES_URL")
                }
            },
        )
    }
}

/** What only Veldt's own pill needs: the permission if missing, its status, its appearance. */
@Composable
private fun OwnPillRows(
    state: FloatingPillState,
    onOpenAppearance: () -> Unit,
    startActivity: (Intent) -> Boolean,
) {
    val context = LocalContext.current
    if (!state.overlayGranted) {
        OverlayPermissionRow(
            onGrant = {
                if (!startActivity(PillIntents.grantOverlay(context.packageName))) {
                    toast(context, "Android's permission page couldn't be opened.")
                }
            },
        )
    }
    state.standDown.message?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
        )
    }
    SettingsLinkRow(icon = Icons.Filled.Palette, label = "Pill appearance", onClick = onOpenAppearance)
}

/**
 * The overlay permission, shown only while it is missing (spec §9), with the button that sends
 * the user to `ACTION_MANAGE_OVERLAY_PERMISSION`. Once granted there is nothing left to ask, so
 * the row goes away rather than staying as a "Granted" line the user has to read past.
 */
@Composable
private fun OverlayPermissionRow(onGrant: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Allow 'Display over other apps'", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Needed to show the pill over other apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onGrant) { Text("Open") }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
    )
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
