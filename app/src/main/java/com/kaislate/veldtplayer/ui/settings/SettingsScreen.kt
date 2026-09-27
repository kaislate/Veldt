// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

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
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel

/**
 * The one settings surface: a three-way theme selector, the hidden folders, ReplayGain, the
 * mobile-data streaming quality, the online-lyrics opt-in, the floating pill, the way in to server
 * accounts, and an About block with the notices it is obliged to carry and, when any link is
 * configured, "Support Veldt" ([SupportSection]). It draws its own header rather
 * than taking the shared `TopAppBar`, same as every non-tab destination — see
 * `VeldtNavHost.TAB_ROUTES`.
 *
 * The pill's appearance options live on their own page, [PillAppearanceScreen], reached from the
 * "Floating pill" section ([FloatingPillSection]).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenNotices: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenPillAppearance: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val mode by vm.themeMode.collectAsStateWithLifecycle()
    val meteredCap by vm.meteredMaxBitRate.collectAsStateWithLifecycle()
    val lyricsOnline by vm.lyricsOnline.collectAsStateWithLifecycle()
    val hiddenFolders by vm.hiddenFolders.collectAsStateWithLifecycle()
    var showingHidden by remember { mutableStateOf(false) }
    val direction = LocalLayoutDirection.current
    val context = LocalContext.current

    // The overlay permission has no changed-broadcast (OverlayPermission's KDoc) — re-read it
    // on ON_RESUME, the same pattern PermissionGate uses for the audio permission: it catches
    // both the user granting it from the ACTION_MANAGE_OVERLAY_PERMISSION screen the pill
    // section launches, and the system revoking it for an unused app while Veldt was backgrounded.
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
        SettingsHeader(title = "Settings", onBack = onBack)

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

        SectionLabel("Library")
        SettingsLinkRow(
            icon = Icons.Filled.VisibilityOff,
            label = "Hidden folders (${hiddenFolders.size})",
            onClick = { showingHidden = true },
        )
        if (showingHidden) {
            HiddenFoldersDialog(
                folders = hiddenFolders,
                onShow = vm::showFolder,
                onDismiss = { showingHidden = false },
            )
        }

        PlaybackSection(vm)

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

        FloatingPillSection(
            vm = vm,
            onOpenAppearance = onOpenPillAppearance,
            startActivity = { startActivitySafely(context, it) },
        )

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
        SupportSection(
            links = SupportLinks.configured(),
            startActivity = { startActivitySafely(context, it) },
        )
    }
}

/**
 * Back affordance and the screen's own [title] — these destinations carry no shared `TopAppBar`.
 * Shared with [PillAppearanceScreen] so a page reached from Settings looks like Settings.
 */
@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, style = MaterialTheme.typography.titleLarge)
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
internal fun ThemeOptionRow(
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
 * One on/off setting, with a sentence saying what turning it on does when the label alone does
 * not ([explanation] is optional for the switch whose label already says it).
 *
 * A SINGLE `toggleable` target on the row with the [Switch]'s own `onCheckedChange = null`, for
 * the same reason [ThemeOptionRow] is one `selectable`: two targets would be announced as two
 * elements, and `role = Role.Switch` is what makes the row itself read as a switch with its
 * state — label and explanation included.
 */
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    explanation: String? = null,
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
            if (explanation != null) {
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A caption followed by one [ThemeOptionRow] per option — the same shape as the streaming
 * quality list, generalised so the pill's several discrete appearance choices (anchor, width,
 * wave style, wave colour, hide delay, transport buttons) do not each repeat it.
 */
@Composable
internal fun <T> RadioGroup(
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
 * The hidden folders (Step 5 spec §4), each with the one thing to do to it here: show it again.
 *
 * A dialog rather than a destination because the list is short and has one verb — a screen of its
 * own would be a back stack entry for a list most users keep at zero or one. It stays open as
 * entries are shown, so clearing several is several taps rather than several round trips, and it
 * says so plainly when there is nothing left, rather than closing under the user's thumb.
 *
 * Hiding happens in the Folders tab, by long-pressing a folder; the empty text says where, because
 * this dialog is where someone who wants that feature is most likely to look first.
 */
@Composable
internal fun HiddenFoldersDialog(
    folders: List<HiddenFolder>,
    onShow: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden folders") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (folders.isEmpty()) {
                    Text(
                        "No folders are hidden. Long-press a folder in the Folders tab to hide " +
                            "its music from Songs, Albums, Artists and search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            folder.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onShow(folder.key) }) { Text("Show") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * One row that opens another screen or app: an icon, a label, an optional [caption], and the
 * chevron that says "there is more through here".
 *
 * Generalised from what used to be a private `NoticesRow` when the servers row arrived. Both
 * icons are decorative — `contentDescription = null` — because [label] already names the target
 * and TalkBack would otherwise announce the destination three times.
 */
@Composable
internal fun SettingsLinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (caption != null) {
                Text(
                    caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(18.dp).width(18.dp),
        )
    }
}
