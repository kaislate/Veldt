// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kaislate.veldtplayer.R
import com.kaislate.veldtplayer.ui.browse.SIDE_MARGIN

/**
 * Renders `R.raw.notices` — the bundled third-party licence text — in a scrollable monospace
 * column. Read straight from the raw resource rather than from a repo file: the whole point of
 * Task 6 Step 1 is that this text lives where it actually ships, in the built resources, not
 * only in the source tree.
 */
@Composable
fun NoticesScreen(
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text = remember {
        context.resources.openRawResource(R.raw.notices).bufferedReader().readText()
    }
    val direction = LocalLayoutDirection.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = contentPadding.calculateStartPadding(direction),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(direction),
            ),
    ) {
        NoticesHeader(onBack = onBack)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = SIDE_MARGIN,
                    vertical = 12.dp,
                )
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

/** Back affordance and title — this destination carries no shared `TopAppBar` either. */
@Composable
private fun NoticesHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = SIDE_MARGIN, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text("Third-party notices", style = MaterialTheme.typography.titleLarge)
    }
}
