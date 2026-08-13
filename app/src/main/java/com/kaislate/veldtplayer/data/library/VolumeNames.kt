// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import android.content.Context
import android.os.storage.StorageManager
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Human labels for MediaStore volume names — **the only Android-touching part of this feature**,
 * isolated here so the rest of the tree stays pure and JVM-testable.
 *
 * `StorageVolume.getMediaStoreVolumeName()` is the clean way to match a volume and it is **API 30**
 * while this app's floor is 29, so on 29 the fallback is matching `getUuid()?.lowercase()`.
 *
 * **That fallback is a guess and is unverified on hardware** — neither device on this fleet has an
 * SD card (pre-flight survey, 2026-08-14: 80/80 rows are `external_primary`). The design therefore
 * degrades rather than depending on it: any unmatched non-primary volume is labelled "SD card",
 * and a raw `1234-5678` is never shown as a primary label.
 */
@Singleton
class VolumeNames @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun label(volume: String): String = when {
        volume == UNFILED_KEY -> "Unfiled"
        volume == MediaStore.VOLUME_EXTERNAL_PRIMARY -> "Internal storage"
        volume == VOLUME_UNKNOWN -> "Other storage"
        else -> described(volume) ?: "SD card"
    }

    private fun described(volume: String): String? = runCatching {
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        manager.storageVolumes.firstOrNull { sv ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                sv.mediaStoreVolumeName == volume
            } else {
                sv.uuid?.lowercase() == volume
            }
        }?.getDescription(context)
    }.getOrNull()
}
