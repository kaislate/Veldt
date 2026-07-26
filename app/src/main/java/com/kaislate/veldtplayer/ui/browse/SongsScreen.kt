package com.kaislate.veldtplayer.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaislate.veldtplayer.ui.components.SongRow
import com.kaislate.veldtplayer.ui.motion.rememberReducedMotion
import com.kaislate.veldtplayer.ui.motion.staggeredEntrance
import com.kaislate.veldtplayer.ui.theme.ColorExtractor

@Composable
fun SongsScreen(
    vm: BrowseViewModel,
    audioGranted: Boolean,
    onRequestAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // collectAsStateWithLifecycle, not collectAsState: every VM flow here is
    // WhileSubscribed, and a backgrounded screen must let the upstream stop.
    val songs by vm.songs.collectAsStateWithLifecycle()
    val reduced = rememberReducedMotion()
    // Browse rows use the neutral fallback palette; per-track colour is a now-playing
    // concern (a list themed by 300 different covers would be noise, not craft).
    val palette = ColorExtractor.extract(null)

    if (!audioGranted) {
        EmptyState(
            title = "Veldt needs access to your music",
            body = "Grant audio access and Veldt will index everything on this device.",
            actionLabel = "Grant access",
            onAction = onRequestAudio,
            modifier = modifier,
        )
        return
    }

    if (songs.isEmpty()) {
        EmptyState(
            title = "No songs yet",
            body = "Nothing turned up in the media index. Run a scan once your music is on the device.",
            actionLabel = "Scan library",
            onAction = vm::scan,
            modifier = modifier,
        )
        return
    }

    // The Scaffold hands back exactly the system-bar insets, which leaves the first row
    // flush against the status bar and the last one flush against the nav bar. 8dp of
    // content padding is the difference between a list that sits in the screen and one
    // that is jammed into it.
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song = song,
                palette = palette,
                onClick = { vm.play(songs, index) },
                modifier = Modifier.staggeredEntrance(index, reduced),
            )
        }
    }
}

/** Shared empty/permission surface — never a blank screen (spec §13). */
@Composable
fun EmptyState(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onAction) { Text(actionLabel) }
    }
}
