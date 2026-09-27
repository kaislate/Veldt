// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.BuildConfig
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.util.IslandPosition
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel

/**
 * The one settings surface: a three-way theme selector, the mobile-data streaming quality, the
 * online-lyrics opt-in, the way in to server accounts, and an About block with the notices it is
 * obliged to carry. It draws its own header rather than taking the shared `TopAppBar`, same as
 * every non-tab destination — see `VeldtNavHost.TAB_ROUTES`.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenAccounts: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val meteredCap by vm.meteredMaxBitRate.collectAsStateWithLifecycle()
    val lyricsOnline by vm.lyricsOnline.collectAsStateWithLifecycle()
    val pillMode by vm.pillMode.collectAsStateWithLifecycle()
    val pillForceBuiltIn by vm.pillForceBuiltIn.collectAsStateWithLifecycle()
    val wispInstalled by vm.wispInstalled.collectAsStateWithLifecycle()
    val overlayGranted by vm.overlayPermissionGranted.collectAsStateWithLifecycle()
    val pillStandDown by vm.pillStandDown.collectAsStateWithLifecycle()
    val pillAnchor by vm.pillAnchor.collectAsStateWithLifecycle()
    val pillWidthDp by vm.pillWidthDp.collectAsStateWithLifecycle()
    val pillWaveStyle by vm.pillWaveStyle.collectAsStateWithLifecycle()
    val pillWaveColor by vm.pillWaveColor.collectAsStateWithLifecycle()
    val pillArtCrossfade by vm.pillArtCrossfade.collectAsStateWithLifecycle()
    val pillHideDelayMs by vm.pillHideDelayMs.collectAsStateWithLifecycle()
    val pillTransportButtons by vm.pillTransportButtons.collectAsStateWithLifecycle()
    val direction = LocalLayoutDirection.current
    val context = LocalContext.current

    // The overlay permission has no changed-broadcast (OverlayPermission's KDoc) — re-read it
    // on ON_RESUME, the same pattern PermissionGate uses for the audio permission: it catches
    // both the user granting it from the ACTION_MANAGE_OVERLAY_PERMISSION screen this section
    // launches, and the system revoking it for an unused app while Veldt was backgrounded.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshOverlayPermissionStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            )
            .verticalScroll(rememberScrollState())
            .padding(bottom = contentPadding.calculateBottomPadding()),
    ) {
        SettingsHeader(onBack = onBack)

        SectionLabel("Appearance")
        ThemeOptionRow(
            label = "Light",
            selected = mode == ThemeMode.LIGHT,
            onSelect = { vm.setThemeMode(ThemeMode.LIGHT) },
        )
        ThemeOptionRow(
            label = "Dark",
            selected = mode == ThemeMode.DARK,
            onSelect = { vm.setThemeMode(ThemeMode.DARK) },
        )
        ThemeOptionRow(
            label = "Follow system",
            selected = mode == ThemeMode.SYSTEM,
            onSelect = { vm.setThemeMode(ThemeMode.SYSTEM) },
        )

        SectionLabel("Streaming")
        Text(
            "On mobile data",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
        )
        // Metered networks only (owner decision): Wi-Fi and other unmetered networks always
        // stream original quality, whatever is chosen here.
        listOf(
            0 to "Original quality",
            320 to "320 kbps",
            192 to "192 kbps",
            128 to "128 kbps",
        ).forEach { (kbps, label) ->
            ThemeOptionRow(
                label = label,
                selected = meteredCap == kbps,
                onSelect = { vm.setMeteredMaxBitRate(kbps) },
            )
        }

        SectionLabel("Lyrics")
        SwitchRow(
            label = "Find lyrics online (LRCLIB)",
            explanation = "Sends the song's title, artist, album and length to lrclib.net when " +
                "you open lyrics for a song that has none.",
            checked = lyricsOnline,
            onCheckedChange = vm::setLyricsOnline,
        )

        SectionLabel("Floating pill")
        ThemeOptionRow(
            label = "Built-in",
            selected = pillMode == PillMode.BUILT_IN,
            onSelect = { vm.setPillMode(PillMode.BUILT_IN) },
        )
        ThemeOptionRow(
            label = "Use Veldt Wisp",
            selected = pillMode == PillMode.USE_WISP,
            onSelect = { vm.setPillMode(PillMode.USE_WISP) },
        )
        ThemeOptionRow(
            label = "Off",
            selected = pillMode == PillMode.OFF,
            onSelect = { vm.setPillMode(PillMode.OFF) },
        )

        if (pillMode == PillMode.BUILT_IN) {
            OverlayPermissionRow(
                granted = overlayGranted,
                onGrant = {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
            )

            pillStandDown.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
                )
            }

            if (wispInstalled) {
                Text(
                    "Veldt Wisp is installed — Veldt defers to it, so its own pill shows instead " +
                        "of Veldt's.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
                )
                SwitchRow(
                    label = "Use built-in anyway",
                    explanation = "Shows Veldt's own pill even though Veldt Wisp is installed. " +
                        "Disable Veldt Wisp's pill for Veldt's session to avoid seeing two.",
                    checked = pillForceBuiltIn,
                    onCheckedChange = vm::setPillForceBuiltIn,
                )
            }

            RadioGroup(
                caption = "Anchor",
                options = listOf(
                    IslandPosition.TOP_LEFT to "Top left",
                    IslandPosition.TOP_CENTER to "Top center",
                    IslandPosition.TOP_RIGHT to "Top right",
                    IslandPosition.BOTTOM_LEFT to "Bottom left",
                    IslandPosition.BOTTOM_CENTER to "Bottom center",
                    IslandPosition.BOTTOM_RIGHT to "Bottom right",
                ),
                selected = pillAnchor,
                onSelect = vm::setPillAnchor,
            )

            RadioGroup(
                caption = "Width",
                options = listOf(120 to "Narrow", 160 to "Default", 200 to "Wide", 240 to "Extra wide"),
                selected = pillWidthDp,
                onSelect = vm::setPillWidthDp,
            )

            RadioGroup(
                caption = "Wave style",
                options = listOf(
                    "wisptrail" to "Wisptrail",
                    "mercury" to "Mercury",
                    "silk" to "Silk",
                    "sparks" to "Sparks",
                    "aurora" to "Aurora",
                ),
                selected = pillWaveStyle,
                onSelect = vm::setPillWaveStyle,
            )

            RadioGroup(
                caption = "Wave colour",
                options = listOf(
                    "accent-light" to "Accent, lightened",
                    "auto" to "Automatic",
                    // Persisted as "white" (Wisp's value); it draws the solved TEXT tone,
                    // which is dark on a light ground, so the label says what it does.
                    "white" to "Match text",
                ),
                selected = pillWaveColor,
                onSelect = vm::setPillWaveColor,
            )

            SwitchRow(
                label = "Album art crossfade",
                explanation = "Fades between album art instead of cutting when the track changes.",
                checked = pillArtCrossfade,
                onCheckedChange = vm::setPillArtCrossfade,
            )

            RadioGroup(
                caption = "Hide delay after pause",
                options = listOf(
                    10_000L to "10 seconds",
                    15_000L to "15 seconds",
                    25_000L to "25 seconds",
                    45_000L to "45 seconds",
                ),
                selected = pillHideDelayMs,
                onSelect = vm::setPillHideDelayMs,
            )

            RadioGroup(
                caption = "Transport buttons",
                options = listOf(
                    "off" to "None",
                    "play" to "Play/pause",
                    "play-next" to "Play/pause and next",
                    "prev-play-next" to "Previous, play/pause and next",
                ),
                selected = pillTransportButtons,
                onSelect = vm::setPillTransportButtons,
            )
        }

        SectionLabel("Servers")
        SettingsLinkRow(
            icon = Icons.Filled.Dns,
            label = "Music servers",
            onClick = onOpenAccounts,
        )

        SectionLabel("About")
        Column(modifier = Modifier.padding(horizontal = SIDE_MARGIN)) {
            Text("Veldt", style = MaterialTheme.typography.titleMedium)
            Text(
                "Version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "GNU General Public License v3.0 or later",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        SettingsLinkRow(
            icon = Icons.Filled.Description,
            label = "Third-party notices",
            onClick = onOpenNotices,
        )
    }
}

/** Back affordance and the screen's own title — this destination carries no shared `TopAppBar`. */
@Composable
private fun SettingsHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("Settings", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * One radio option — an entry of the three-way theme pill, or of the mobile-data quality list.
 * [selected] marks the value currently in effect.
 *
 * A SINGLE `selectable` target on the row, not `clickable` on the row plus the [RadioButton]'s
 * own `onClick`: two targets meant TalkBack announced the radio and the row as separate
 * elements, neither one carrying the "selected" state consistently. `role = Role.RadioButton`
 * is what makes the row itself announce as a radio option with its selected state; the
 * [RadioButton]'s `onClick = null` stops it from being a second, redundant target nested inside
 * the row's.
 */
@Composable
private fun ThemeOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One on/off setting with a sentence saying what turning it on does.
 *
 * A SINGLE `toggleable` target on the row with the [Switch]'s own `onCheckedChange = null`, for
 * the same reason [ThemeOptionRow] is one `selectable`: two targets would be announced as two
 * elements, and `role = Role.Switch` is what makes the row itself read as a switch with its
 * state — label and explanation included.
 */
@Composable
private fun SwitchRow(
    label: String,
    explanation: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                explanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A caption followed by one [ThemeOptionRow] per option — the same shape as the streaming
 * quality list above, generalised so the pill's several discrete appearance choices (anchor,
 * width, wave style, wave colour, hide delay, transport buttons) do not each repeat it.
 */
@Composable
private fun <T> RadioGroup(
    caption: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        caption,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = SIDE_MARGIN, vertical = 4.dp),
    )
    options.forEach { (value, label) ->
        ThemeOptionRow(label = label, selected = selected == value, onSelect = { onSelect(value) })
    }
}

/**
 * The overlay permission's status, and — only while it is missing — the button that sends the
 * user to `ACTION_MANAGE_OVERLAY_PERMISSION` to grant it (spec §3/§4).
 *
 * Once granted there is nothing left to do here, so the button disappears rather than staying
 * present and disabled: a permission the user already granted is not a decision the row is
 * still asking them to make.
 */
@Composable
private fun OverlayPermissionRow(
    granted: Boolean,
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Overlay permission", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Needed to show the pill over other apps",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) {
            TextButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

/**
 * One row that opens another screen: an icon, a label, and the chevron that says "there is more
 * through here".
 *
 * Generalised from what used to be a private `NoticesRow` when the servers row arrived. Both
 * icons are decorative — `contentDescription = null` — because [label] already names the target
 * and TalkBack would otherwise announce the destination three times.
 */
@Composable
private fun SettingsLinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = SIDE_MARGIN, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(18.dp).width(18.dp),
        )
    }
}
