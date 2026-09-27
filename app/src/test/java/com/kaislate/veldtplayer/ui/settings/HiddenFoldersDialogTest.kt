// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings list of hidden folders (Step 5 spec §4): each entry's "Show" names its own key. */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class HiddenFoldersDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `each folder is listed with a Show that shows that folder`() {
        val shown = ArrayList<String>()
        compose.setContent {
            HiddenFoldersDialog(
                folders = listOf(
                    HiddenFolder("external_primary:Podcasts", "Internal storage › Podcasts"),
                    HiddenFolder("aaaa-bbbb:BACKUP/Downloads", "SD card › BACKUP › Downloads"),
                ),
                onShow = { shown += it },
                onDismiss = {},
            )
        }
        compose.onNodeWithText("Internal storage › Podcasts").assertExists()
        compose.onNodeWithText("SD card › BACKUP › Downloads").assertExists()
        compose.onAllNodesWithText("Show")[1].performClick()
        assertEquals(listOf("aaaa-bbbb:BACKUP/Downloads"), shown)
    }

    @Test fun `with nothing hidden it says where hiding happens`() {
        compose.setContent { HiddenFoldersDialog(folders = emptyList(), onShow = {}, onDismiss = {}) }
        compose.onNodeWithText("Long-press a folder in the Folders tab", substring = true)
            .assertExists()
    }
}
