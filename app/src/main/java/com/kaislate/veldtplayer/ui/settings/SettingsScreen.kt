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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.BuildConfig
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN
import com.kaislate.veldtplayer.ui.browse.SectionLabel

/**
 * The one settings surface: a three-way theme selector, the way in to server accounts, and an
 * About block with the notices it is obliged to carry. It draws its own
 * header rather than taking the shared `TopAppBar`, same as every non-tab destination — see
 * `VeldtNavHost.TAB_ROUTES`.
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
    val direction = LocalLayoutDirection.current

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
 * One entry of the three-way theme pill — [selected] marks the mode currently in effect.
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
