// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.theme

import com.kaislate.veldtplayer.data.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure JVM. Both tests are about ONE decision — [resolveDark] — from two angles: a source scan
 * that nothing else makes the decision, and a value-level check of the decision itself.
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

        val offenders = ktFiles
            .filter { it.name != "Theme.kt" }
            .filter { it.readText().contains("isSystemInDarkTheme(") }
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
     * This pins the CURRENT parent so a future edit toward a Light/DayNight parent fails here
     * instead of silently reintroducing a second theme source that disagrees with
     * [resolveDark]. `Theme.Veldt` is still legitimately dark-only — see the KDoc comment in
     * themes.xml for why (it paints only the pre-Compose launch window, which cannot read the
     * user's stored Light/Dark/Follow-system choice synchronously) — so if that ever changes,
     * update BOTH this assertion and the themes.xml comment together; do not just delete the
     * guard.
     */
    @Test fun `themes xml launch-window parent is pinned dark, not a second theme source`() {
        val root = mainSourceRoot()
        val themesXml = File(root, "res/values/themes.xml")
        assertTrue("expected ${themesXml.absolutePath} to exist", themesXml.isFile)

        // The actual <style ... parent="..."> declaration ONLY — not the whole file, which
        // legitimately talks about "DayNight" and "Light" in its explanatory comment. Matching
        // the tag itself is what keeps this guard from being tripped by prose describing the
        // very thing it guards against.
        val styleTag = Regex("""<style\s+name="Theme\.Veldt"[^>]*>""")
            .find(themesXml.readText())
            ?.value
            ?: error("could not find the <style name=\"Theme.Veldt\" ...> declaration in " +
                "${themesXml.absolutePath}; themes.xml was restructured — update this guard")

        assertTrue(
            "Theme.Veldt's parent changed away from the pinned-dark launch-window style. If " +
                "that is deliberate, update this assertion AND themes.xml's comment explaining " +
                "why (FINDING 2, whole-branch review) — do not just delete this guard.",
            styleTag.contains("parent=\"Theme.Material3.DynamicColors.Dark\""),
        )
        assertTrue(
            "themes.xml must not gain a Light/DayNight parent without this guard being " +
                "updated: that would make it a second, undocumented theme source disagreeing " +
                "with VeldtTheme.resolveDark().",
            !styleTag.contains("DynamicColors.Light") && !styleTag.contains("DayNight"),
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
