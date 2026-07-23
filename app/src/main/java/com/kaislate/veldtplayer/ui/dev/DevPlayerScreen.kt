package com.kaislate.veldtplayer.ui.dev

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaislate.veldtplayer.data.media.MediaSessionBus

@Composable
fun DevPlayerScreen(vm: DevPlayerViewModel = viewModel()) {
    val isPlaying by vm.isPlaying.collectAsState()
    val busState by MediaSessionBus.playbackState.collectAsState()
    val meta by MediaSessionBus.metadata.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::playUri) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Veldt — playback foundation (P1.1)")
        Button(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Pick audio file") }
        Button(onClick = vm::togglePlayPause) { Text(if (isPlaying) "Pause" else "Play") }
        Button(onClick = vm::seekForward) { Text("Seek +10s") }
        Text("controller.isPlaying = $isPlaying")
        Text("bus.playbackState = $busState")
        Text("bus title = ${meta?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)}")
    }
}
