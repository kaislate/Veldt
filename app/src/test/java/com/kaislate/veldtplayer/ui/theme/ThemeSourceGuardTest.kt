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
