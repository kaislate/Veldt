// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Compose UI test toolkit, proven to run in THIS suite before anything relies on it.
 *
 * Every other Compose test here would fail the same way if the rule could not start its host
 * activity (a missing `ui-test-manifest`) or could not drive a recomposition under Robolectric,
 * and that failure would read as a bug in whatever screen it was testing. This one has no
 * screen in it: a click that changes state, and the text that state produces. Red here means
 * the harness, not the app.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class ComposeTestInfraTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `a click recomposes the text it changed`() {
        compose.setContent {
            var taps by remember { mutableIntStateOf(0) }
            Text(
                text = "taps: $taps",
                modifier = Modifier.testTag("counter").clickable { taps++ },
            )
        }

        compose.onNodeWithTag("counter").assertTextEquals("taps: 0")
        compose.onNodeWithTag("counter").performClick()
        compose.onNodeWithTag("counter").assertTextEquals("taps: 1")
    }
}
