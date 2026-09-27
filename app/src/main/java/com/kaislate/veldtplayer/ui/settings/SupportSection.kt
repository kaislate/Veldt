// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * "Support Veldt" in Settings → About: one row, opening a small dialog with a button per
 * configured [links] entry, each handed to the browser through [startActivity] — the same seam,
 * answering false when nothing could take the intent, that "Get Veldt Wisp" uses.
 *
 * Nothing else: no request, no tracking, no reminder, no badge. With no links configured the row
 * is not there at all, rather than opening an empty dialog.
 */
@Composable
internal fun SupportSection(
    links: List<SupportLink>,
    startActivity: (Intent) -> Boolean,
) {
    if (links.isEmpty()) return
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    SettingsLinkRow(
        icon = Icons.Filled.VolunteerActivism,
        label = "Support Veldt",
        caption = "Optional. Veldt stays free either way",
        onClick = { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Support Veldt") },
            text = {
                Column {
                    Text(
                        "Opens in your browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    links.forEach { link ->
                        TextButton(
                            onClick = {
                                if (!startActivity(link.intent())) {
                                    Toast.makeText(
                                        context,
                                        "No app can open links. The page is ${link.url}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            },
                        ) { Text(link.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
