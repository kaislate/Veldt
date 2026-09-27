// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.pill.PillStandDown
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every row the "Floating pill" section can show, by its text, in screen order. */
internal val FLOATING_PILL_ROWS = listOf(
    "A now-playing pill when you leave Veldt",
    "Veldt Wisp is showing the pill",
    "Open Veldt Wisp",
    "Use Veldt's own pill instead",
    "Turn off Veldt Wisp's pill for Veldt so you don't see two.",
    "Allow 'Display over other apps'",
    PillStandDown.PERMISSION_LOST.message!!,
    "Pill appearance",
    "Get Veldt Wisp",
    "The standalone pill, for every music app",
)

/** Which of [FLOATING_PILL_ROWS] are on screen, in that order. */
internal fun ComposeContentTestRule.shownPillRows(): List<String> =
    FLOATING_PILL_ROWS.filter { onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() }

/** The "Floating pill" switch itself, not the section label above it. */
internal val ComposeContentTestRule.pillSwitch: SemanticsNodeInteraction
    get() = onNode(hasText("Floating pill") and isToggleable())

/**
 * The Settings "Floating pill" section (Step 5 spec §9), from a plain [FloatingPillState]: which
 * rows each state shows, and what each row sends through the `startActivity` seam (no browser or
 * settings page is ever opened). [FloatingPillSectionBoundTest] covers it bound to the real
 * view model.
 *
 * The row assertions compare the full list of rows present against the expected list, so a row
 * that shows where it should not fails as loudly as one that is missing.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class FloatingPillSectionTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun shownRows(): List<String> = compose.shownPillRows()

    private val started = ArrayList<Intent>()
    private val enabledWrites = ArrayList<Boolean>()
    private var appearanceOpened = 0

    /** The state on screen; [show] sets content once, then only changes this. */
    private var current: MutableState<FloatingPillState>? = null

    private fun show(state: FloatingPillState) {
        current?.let { it.value = state; compose.waitForIdle(); return }
        val holder = mutableStateOf(state)
        current = holder
        compose.setContent {
            Column {
                FloatingPillSection(
                    state = holder.value,
                    onEnabledChange = { enabledWrites += it },
                    onForceBuiltInChange = {},
                    onOpenAppearance = { appearanceOpened++ },
                    startActivity = { started += it; true },
                )
            }
        }
    }

    private fun state(
        enabled: Boolean = true,
        wisp: Boolean = false,
        force: Boolean = false,
        granted: Boolean = false,
        standDown: PillStandDown = PillStandDown.NONE,
    ) = FloatingPillState(enabled, wisp, force, granted, standDown)

    private val theSwitch get() = compose.pillSwitch

    // ---- which rows each state shows ----

    @Test fun `on, no Wisp, permission missing`() {
        show(state())
        theSwitch.assertIsOn()
        assertEquals(
            listOf(
                "A now-playing pill when you leave Veldt",
                "Allow 'Display over other apps'",
                "Pill appearance",
                "Get Veldt Wisp",
                "The standalone pill, for every music app",
            ),
            shownRows(),
        )
    }

    @Test fun `on, no Wisp, permission granted has no permission row`() {
        show(state(granted = true))
        assertEquals(
            listOf(
                "A now-playing pill when you leave Veldt",
                "Pill appearance",
                "Get Veldt Wisp",
                "The standalone pill, for every music app",
            ),
            shownRows(),
        )
    }

    @Test fun `a stand-down is shown only while there is one`() {
        show(state(granted = true, standDown = PillStandDown.PERMISSION_LOST))
        assertEquals(
            listOf(
                "A now-playing pill when you leave Veldt",
                PillStandDown.PERMISSION_LOST.message!!,
                "Pill appearance",
                "Get Veldt Wisp",
                "The standalone pill, for every music app",
            ),
            shownRows(),
        )
    }

    /** Wisp draws the pill: Veldt's own permission and appearance would configure nothing. */
    @Test fun `on, Wisp installed, no override, permission missing`() {
        show(state(wisp = true))
        compose.onNode(hasText("Use Veldt's own pill instead") and isToggleable()).assertIsOff()
        assertEquals(
            listOf(
                "Veldt Wisp is showing the pill",
                "Open Veldt Wisp",
                "Use Veldt's own pill instead",
            ),
            shownRows(),
        )
    }

    @Test fun `on, Wisp installed, override on, permission missing`() {
        show(state(wisp = true, force = true))
        compose.onNode(hasText("Use Veldt's own pill instead") and isToggleable()).assertIsOn()
        assertEquals(
            listOf(
                "A now-playing pill when you leave Veldt",
                "Open Veldt Wisp",
                "Use Veldt's own pill instead",
                "Turn off Veldt Wisp's pill for Veldt so you don't see two.",
                "Allow 'Display over other apps'",
                "Pill appearance",
            ),
            shownRows(),
        )
    }

    @Test fun `on, Wisp installed, override on, permission granted has no permission row`() {
        show(state(wisp = true, force = true, granted = true))
        assertEquals(
            listOf(
                "A now-playing pill when you leave Veldt",
                "Open Veldt Wisp",
                "Use Veldt's own pill instead",
                "Turn off Veldt Wisp's pill for Veldt so you don't see two.",
                "Pill appearance",
            ),
            shownRows(),
        )
    }

    @Test fun `off shows nothing but the switch, with or without Wisp`() {
        val shown = listOf(false, true).map { wisp ->
            show(state(enabled = false, wisp = wisp, force = true, standDown = PillStandDown.ATTACH_REFUSED))
            theSwitch.assertIsOff()
            shownRows()
        }
        val onlyTheCaption = listOf("A now-playing pill when you leave Veldt")
        assertEquals(listOf(onlyTheCaption, onlyTheCaption), shown)
    }

    // ---- what each row does ----

    @Test fun `the switch and Pill appearance call back`() {
        show(state())
        theSwitch.performClick()
        compose.onNodeWithText("Pill appearance").performClick()
        assertEquals(listOf(false), enabledWrites)
        assertEquals(1, appearanceOpened)
    }

    @Test fun `Get Veldt Wisp views the latest release in a browser`() {
        show(state())
        compose.onNodeWithText("Get Veldt Wisp").performClick()
        val intent = started.single()
        assertEquals(
            listOf<Any?>(
                Intent.ACTION_VIEW,
                Uri.parse("https://github.com/kaislate/veldt-wisp/releases/latest"),
                setOf(Intent.CATEGORY_BROWSABLE),
            ),
            listOf<Any?>(intent.action, intent.data, intent.categories),
        )
    }

    @Test fun `Open Veldt Wisp launches Wisp's own launcher activity`() {
        val launcher = ComponentName("com.kaislate.veldt", "com.kaislate.veldt.MainActivity")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(launcher)
            addIntentFilterForActivity(
                launcher,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
            )
        }
        show(state(wisp = true))
        compose.onNodeWithText("Open Veldt Wisp").performClick()
        val intent = started.single()
        assertEquals(
            listOf<Any?>(Intent.ACTION_MAIN, launcher),
            listOf<Any?>(intent.action, intent.component),
        )
    }

    @Test fun `the permission row opens Veldt's own overlay permission page`() {
        show(state())
        compose.onNodeWithText("Open").performClick()
        val intent = started.single()
        assertEquals(
            listOf<Any?>(
                AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            listOf<Any?>(intent.action, intent.data),
        )
    }

    /** No browser at all: the ActivityNotFoundException is caught and answered as false. */
    @Test fun `starting an intent nothing can take answers false instead of throwing`() {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .checkActivities(true)
        val intent = PillIntents.getWisp().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(false, startActivitySafely(context, intent))
    }
}
