// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

/** The separator between crumbs. A glyph, not a slash: the crumbs already carry the path. */
private const val SEPARATOR = "›"

/**
 * `Internal storage › Music › Beck › Sea Change`.
 *
 * Horizontally scrollable, because a real path is longer than a phone is wide and truncating the
 * MIDDLE of it would hide exactly the folder names that tell two similar paths apart.
 *
 * The last crumb is the folder the user is looking at: emphasised and inert. Elided ancestors are
 * inert too — they are drawn so elision never costs the user the truth about where they are, but
 * there is no destination for a level the design deliberately removed. [FolderCrumb.route] is null
 * for both.
 */
@Composable
fun FolderBreadcrumb(
    crumbs: List<FolderCrumb>,
    onCrumb: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = SEPARATOR,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            val current = index == crumbs.lastIndex
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        // No `clickable(enabled = false)`: a disabled clickable still occupies the
                        // node and reports itself to accessibility as a control that does nothing.
                        if (crumb.route == null) Modifier
                        else Modifier.clickable { onCrumb(crumb.route) },
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Tapping an ancestor **POPS to it** — it does not navigate forward.
 *
 * That is the difference between a back stack that shrinks when the user goes up and one that grows
 * a duplicate copy of every ancestor: `Music › Beck › Sea Change`, tap `Music`, tap `Beck` again,
 * and the forward-only form leaves two `Beck` entries to back out of.
 *
 * The fallback is reachable rather than defensive — a crumb whose entry was never on this stack,
 * which is what a deep link or a restored-then-trimmed stack produces — and it is the branch that
 * duplicates rows if it is wrong. **Both branches are exercised in Task 6.**
 */
internal fun NavController.openCrumb(route: String) {
    val popped = popBackStack(route, inclusive = false)
    if (!popped) navigate(route) { launchSingleTop = true }
}
