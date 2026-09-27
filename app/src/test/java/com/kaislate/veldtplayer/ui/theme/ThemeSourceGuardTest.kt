// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import com.kaislate.veldtplayer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM. Every test is about ONE decision — which ground a [ThemeMode] resolves to — from
 * several angles: a source scan that nothing but Theme.kt asks the system, a check that the
 * launch-window XML holds one style per mode and nothing more, the mode-to-style map
 * [LaunchTheme] uses, and a value-level check of [resolveDark] itself.
 */
class ThemeSourceGuardTest {

    /**
     * `src/main` under the `app` module, whichever directory Gradle chose to run tests from.
     * Resolved rather than assumed, same as [com.kaislate.veldtplayer.data.library.SourceIdLiteralTest]:
     * a wrong root would find zero files, and an empty offender list is a *passing-looking*
     * result for a test that is really asking "is anything else deciding the theme?".
     */
    private fun mainSourceRoot(): File =
        listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    /**
     * Theme resolution lives in exactly one place. A screen that calls `isSystemInDarkTheme()`
     * itself silently ignores the user's Light/Dark choice — it would look correct on a
     * follow-system device and wrong for anyone who picked a mode, which is the hardest kind of
     * bug to notice. Failure message teaches the rule.
     */
    @Test fun `only VeldtTheme resolves the system theme`() {
        val root = mainSourceRoot()
        val ktFiles = root.walkTopDown().filter { it.extension == "kt" }.toList()

        // A wrong CWD would make the offender scan below vacuously green.
        assertTrue(
            "expected to scan the whole app source set from ${root.absolutePath}, " +
                "found only ${ktFiles.size} .kt files",
            ktFiles.size >= 40,
        )

        // Two spellings of the same question: Compose's, and the Configuration's night bit that
        // code outside composition (MainActivity, before its first frame) would read instead.
        val offenders = ktFiles
            .filter { it.name != "Theme.kt" }
            .filter { file ->
                val text = file.readText()
                text.contains("isSystemInDarkTheme(") || text.contains("UI_MODE_NIGHT_MASK")
            }
            .map { it.name }
        assertEquals("theme resolution must live only in Theme.kt; found in: $offenders",
            emptyList<String>(), offenders)
    }

    /**
     * FINDING 2 (whole-branch review): `res/values/themes.xml` is a SECOND theme source, and
     * being XML rather than Kotlin, it is invisible to the `.kt`-only scan above — that is
     * exactly how an earlier version of this file carried a stale, false rationale ("the colour
     * pipeline has no light branch, so the app is dark on every phone") for a premise this
     * branch deleted. A `.kt`-only guard lets a real theme decision made outside Kotlin hide
     * from every review sweep that greps for `.kt` files.
     *
     * Finding 10 then made the XML carry a real decision on purpose: which window theme a launch
     * gets. It is still not a second theme SOURCE, and this pins why. There are exactly three
     * window styles, one per [ThemeMode]: the manifest's `Theme.Veldt` is DayNight (the starting
     * window follows the system, or on API 31+ the per-app night mode `LaunchTheme` hands it),
     * and the two explicit ones name their ground outright, so the user's choice beats the
     * system's night mode. `LaunchTheme` is the only code that picks between them. If any
     * parent changes, update this assertion AND themes.xml's comment together; do not just
     * delete the guard.
     *
     * No `values-night` themes file is allowed either: it would silently re-parent one of these
     * styles for night mode, a second decision this map could not see.
     */
    @Test fun `themes xml has exactly one window style per theme mode`() {
        val root = mainSourceRoot()
        val themesXml = File(root, "res/values/themes.xml")
        assertTrue("expected ${themesXml.absolutePath} to exist", themesXml.isFile)

        // The actual <style ... parent="..."> declarations ONLY — not the whole file, which
        // legitimately talks about "DayNight" and "Light" in its explanatory comment. Matching
        // the tags themselves is what keeps this guard from being tripped by prose describing
        // the very thing it guards against.
        val parents = Regex("""<style\s+name="([^"]+)"\s+parent="([^"]+)"\s*>""")
            .findAll(themesXml.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

        assertEquals(
            "themes.xml's window styles changed. If that is deliberate, update this map AND " +
                "themes.xml's comment explaining it (findings 2 and 10) — do not just delete " +
                "this guard.",
            mapOf(
                "Theme.Veldt" to "Theme.Material3.DynamicColors.DayNight",
                "Theme.Veldt.Light" to "Theme.Material3.DynamicColors.Light",
                "Theme.Veldt.Dark" to "Theme.Material3.DynamicColors.Dark",
            ),
            parents,
        )
        val nightThemes = File(root, "res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.contains("night") }
            .filter { File(it, "themes.xml").isFile }
            .map { it.name }
        assertEquals("a night-qualified themes.xml re-parents a window style unseen: $nightThemes",
            emptyList<String>(), nightThemes)
    }

    /**
     * The map above names the styles; this pins which [ThemeMode] gets which, since a swapped
     * pair would pass the map and draw every explicit choice inverted.
     */
    @Test fun `each ThemeMode launches with its own window style and night mode`() {
        assertEquals(
            listOf(
                com.kaislate.veldtplayer.R.style.Theme_Veldt_Light to android.app.UiModeManager.MODE_NIGHT_NO,
                com.kaislate.veldtplayer.R.style.Theme_Veldt_Dark to android.app.UiModeManager.MODE_NIGHT_YES,
                com.kaislate.veldtplayer.R.style.Theme_Veldt to android.app.UiModeManager.MODE_NIGHT_AUTO,
            ),
            listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).map {
                LaunchTheme.styleFor(it) to LaunchTheme.nightModeFor(it)
            },
        )
    }

    @Test fun `each ThemeMode maps to the right ground`() {
        // Asserted as a triple: a resolution that collapses two modes together cannot produce
        // three distinct answers, and the failure message shows which pair merged.
        assertEquals(
            listOf(false, true, "follows"),
            listOf(
                resolveDark(ThemeMode.LIGHT, systemDark = true),   // LIGHT wins over the system
                resolveDark(ThemeMode.DARK, systemDark = false),   // DARK wins over the system
                if (resolveDark(ThemeMode.SYSTEM, systemDark = true) &&
                    !resolveDark(ThemeMode.SYSTEM, systemDark = false)) "follows" else "ignores",
            ),
        )
    }
}
