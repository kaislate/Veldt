// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowStorageManager
import org.robolectric.shadows.StorageVolumeBuilder
import java.io.File

/**
 * Labels for MediaStore volume names.
 *
 * Robolectric, because three of the four branches are pure but the fourth consults a real
 * `StorageManager`. `sdk = 34` matches the rest of the suite and takes the API-30+ branch
 * (`getMediaStoreVolumeName()`); **the API-29 `getUuid()` fallback is NOT exercised here** and
 * remains on Task 6's device matrix, as does any real removable card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VolumeNamesTest {

    private lateinit var names: VolumeNames
    private lateinit var shadowStorage: ShadowStorageManager

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        names = VolumeNames(context)
        shadowStorage = Shadow.extract(context.getSystemService(StorageManager::class.java))
    }

    /**
     * `getMediaStoreVolumeName()` returns the normalised fs uuid for a non-primary volume, and
     * `MediaStore.VOLUME_EXTERNAL_PRIMARY` for a primary one.
     *
     * **`setIsPrimary(false)` is load-bearing.** `StorageVolumeBuilder` defaults it to *true*, and a
     * primary volume reports its media-store name as `external_primary` whatever fs uuid it carries
     * — so without this the fixture silently describes internal storage and the uuid match can
     * never succeed. Verified by observing `msvn=external_primary` from the built volume.
     */
    private fun addVolume(fsUuid: String, description: String) {
        shadowStorage.addStorageVolume(
            StorageVolumeBuilder(
                "stub-id", File("/storage/$fsUuid"), description,
                Process.myUserHandle(), Environment.MEDIA_MOUNTED,
            ).setFsUuid(fsUuid).setIsPrimary(false).setIsRemovable(true).build()
        )
    }

    /** A primary volume reports `getMediaStoreVolumeName() == MediaStore.VOLUME_EXTERNAL_PRIMARY`
     *  whatever fs uuid it carries, so this one is matchable by the lookup. */
    private fun addPrimaryVolume(description: String) {
        shadowStorage.addStorageVolume(
            StorageVolumeBuilder(
                "primary", File("/storage/emulated/0"), description,
                Process.myUserHandle(), Environment.MEDIA_MOUNTED,
            ).setIsPrimary(true).setIsRemovable(false).setIsEmulated(true).build()
        )
    }

    @Test fun `the Unfiled bucket is labelled Unfiled`() {
        assertEquals("Unfiled", names.label(UNFILED_KEY))
    }

    /**
     * Our label wins over the platform's, which is a **branch-ORDER** claim and not just a mapping.
     *
     * The registered primary volume is what makes it one. A real `StorageManager` does report a
     * primary volume, its `getMediaStoreVolumeName()` IS `external_primary`, and its
     * `getDescription()` is an OEM string — "Internal shared storage", localised, sometimes branded.
     * So the lookup genuinely matches this volume, and consulting it before this branch would
     * relabel the top-level row on **every device**. With no primary volume registered the fixture
     * cannot tell the two orderings apart: `described()` returns null either way and the test passes
     * for the wrong reason.
     */
    @Test fun `primary storage is labelled Internal storage`() {
        addPrimaryVolume(description = "Internal shared storage (OEM)")
        assertEquals(
            "the StorageManager lookup was consulted before the primary branch",
            "Internal storage",
            names.label(VOLUME_PRIMARY),
        )
    }

    /**
     * A track under no mount root we recognise. It must NOT be labelled "SD card": that would
     * assert a physical card that may not exist, on the one path that exists to say "I don't know".
     */
    @Test fun `an unrecognised mount is labelled Other storage`() {
        assertEquals("Other storage", names.label(VOLUME_UNKNOWN))
    }

    /**
     * The lookup itself — and the only test here that can detect its removal.
     *
     * The platform's own description is deliberately a string the fallback could never produce, so
     * deleting `described()` and returning "SD card" outright turns this red. Its sibling below
     * cannot do that, which is exactly why this one exists.
     */
    @Test fun `a matched removable volume is labelled with the platform's own description`() {
        addVolume(fsUuid = "1234-5678", description = "SanDisk Ultra")
        assertEquals(
            "the StorageManager lookup was not consulted, or did not match on the volume name",
            "SanDisk Ultra",
            names.label("1234-5678"),
        )
    }

    /**
     * The degradation the design leans on: an unmatched non-primary volume is still called a card
     * rather than shown as a raw `1234-5678`.
     *
     * **This assertion cannot detect a missing lookup** — deleting `described()` produces "SD card"
     * too — and it is not claimed to. The volume above is present and does not match, so what is
     * pinned is that a non-match degrades instead of throwing or leaking the raw id. The lookup is
     * pinned by the test above it.
     */
    @Test fun `an unmatched non-primary volume degrades to SD card`() {
        addVolume(fsUuid = "1234-5678", description = "SanDisk Ultra")
        assertEquals("SD card", names.label("aaaa-bbbb"))
    }
}
