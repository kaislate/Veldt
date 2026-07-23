package com.kaislate.veldtplayer.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

/** READ_MEDIA_AUDIO on API 33+, READ_EXTERNAL_STORAGE on ≤32 (spec §6.2). */
private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val songs by vm.songs.collectAsState()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) vm.scan()
    }

    // Populate the library on open when access is already granted (e.g. permission
    // persisted from a prior run). WorkManager's KEEP policy dedupes concurrent scans.
    LaunchedEffect(granted) {
        if (granted) vm.scan()
    }

    // Android 13+ silently suppresses the media notification until POST_NOTIFICATIONS
    // is granted, so Veldt's own now-playing notification (with transport controls)
    // never shows. Request it once on open. Playback works regardless of the result.
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

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Veldt — local library (P1.2)")
        Button(onClick = {
            if (granted) vm.scan() else permLauncher.launch(audioPermission)
        }) {
            Text(if (granted) "Scan library" else "Grant permission & scan")
        }
        Text("${songs.size} songs")
        LazyColumn(Modifier.fillMaxSize()) {
            items(songs, key = { it.id }) { song ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.onSongTap(song) },
                    headlineContent = { Text(song.title) },
                    supportingContent = { Text("${song.artist} — ${song.album}") },
                )
            }
        }
    }
}
