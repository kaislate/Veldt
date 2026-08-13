// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Folder keys carry `/`, `%`, `#` and spaces, so the encode is load-bearing, not defensive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderRouteTest {

    @Test fun `a key with separators, percent, hash and spaces survives the round trip`() {
        val key = "external_primary:Music/A % B #1/Disc 2"
        val route = Destinations.folder(key)
        assertEquals(key, Uri.decode(route.removePrefix("folder/")))
    }

    @Test fun `case is preserved through the route — two folders stay two`() {
        assertEquals(
            "a case-only difference collapsed in the route, merging two real directories",
            listOf("external_primary:Music/beck", "external_primary:Music/Beck"),
            listOf(
                Uri.decode(Destinations.folder("external_primary:Music/beck").removePrefix("folder/")),
                Uri.decode(Destinations.folder("external_primary:Music/Beck").removePrefix("folder/")),
            ),
        )
    }
}
