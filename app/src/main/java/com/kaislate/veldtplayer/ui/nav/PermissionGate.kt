package com.kaislate.veldtplayer.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** READ_MEDIA_AUDIO on API 33+, READ_EXTERNAL_STORAGE on <=32 (spec §6.2). */
private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * Owns both runtime permissions for the app and hands [content] the audio-access state.
 *
 * POST_NOTIFICATIONS matters even though playback works without it: on Android 13+ the
 * media notification is silently SUPPRESSED until it is granted, which is exactly the
 * bug fixed in commit 9a04d4d.
 */
@Composable
fun PermissionGate(
    onGranted: () -> Unit,
    content: @Composable (audioGranted: Boolean, requestAudio: () -> Unit) -> Unit,
) {
    val context = LocalContext.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, audioPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val audioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) onGranted()
    }

    LaunchedEffect(granted) { if (granted) onGranted() }

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

    content(granted) { audioLauncher.launch(audioPermission) }
}
