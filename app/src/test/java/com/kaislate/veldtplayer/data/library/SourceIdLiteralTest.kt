// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **Global Constraint 1, as a check instead of a review rule.**
 *
 * `"local"` is the local source's name, not a fact about the codebase. It is written in exactly one
 * place — [LocalSource]'s `id` initializer — and every other site reads it back from
 * [LibrarySource.id] or from `Song.sourceId`. Rename the source and one line changes.
 *
 * Until this test existed the rule was prose in the plan and in three KDoc blocks, and nothing ran
 * it. A new mapper written months from now with `sourceId = "local"` would be *locally correct*,
 * pass every test in the repo, and only surface as a bug when the source is renamed or a second
 * source ships — at which point the hardcoded rows are indistinguishable from real ones and the
 * damage is in the user's database, not in the diff.
 *
 * It is a source-text check because the property is about the source text: there is no runtime state
 * that distinguishes "read `LocalSource.id`" from "wrote the same characters again". A hardcode and
 * the real thing produce byte-identical behaviour **today**, which is precisely why no behavioural
 * test can ever catch one and why this file is the only thing that can.
 *
 * Scope note: `src/test` is deliberately NOT scanned. Fixtures may name a source literally — GC 1
 * says so — and `PlaylistRepositoryTest` relies on naming one `"not-local"` to catch a hardcode.
 */
class SourceIdLiteralTest {

    /** The Kotlin string literal, quotes included, that this test counts. */
    private val literal = "\"local\""

    private val explanation = """
        |Global Constraint 1: the source id string is written ONCE, in LocalSource's `id`
        |initializer, and read everywhere else from LibrarySource.id or Song.sourceId.
        |
        |If you added a hardcoded source id: don't. Take it from the LibrarySource you already
        |have (`source.id`), or from the Song/entry's own `sourceId` field, so that renaming the
        |source or adding a second one does not leave orphaned rows nobody can attribute.
        |
        |If you only MENTIONED the id in a comment or KDoc: write it in backticks (`local`), not
        |as a quoted string. This check counts characters, not syntax, and prose that looks like a
        |literal is prose that hides one.
        |
        |Expected exactly the LocalSource occurrence below; actual occurrences follow.
    """.trimMargin()

    /**
     * `src/main` under the `app` module, whichever directory Gradle chose to run tests from.
     * Resolved rather than assumed: a wrong root would find zero files, and "zero occurrences" is a
     * *passing-looking* number for a test that is really asking "is it more than one?". The file
     * count assertion below is what stops that from being silent.
     */
    private fun mainSourceRoot(): File =
        listOf(File("src/main"), File("app/src/main"), File("../app/src/main"))
            .firstOrNull { it.isDirectory }
            ?: error("cannot locate app/src/main from ${File(".").absolutePath}")

    @Test
    fun `the local source id is written in exactly one place under src-main`() {
        val root = mainSourceRoot()
        val kotlinFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        // Guards the guard: if the walk found nothing (wrong working directory, moved module) the
        // occurrence count would be 0 and this test would report a clean bill of health for a
        // codebase it never read.
        assertTrue(
            "expected to scan the whole app source set from ${root.absolutePath}, " +
                "found only ${kotlinFiles.size} .kt files",
            kotlinFiles.size >= 40,
        )

        val hits = kotlinFiles.sortedBy { it.path }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (line.contains(literal)) Triple(file.name, i + 1, line.trim()) else null
            }
        }

        // Asserted without line numbers, so an unrelated edit above `LocalSource.id` cannot turn
        // this into a false alarm; the numbers ride along in the message, where they are useful and
        // cost nothing when they drift.
        assertEquals(
            explanation + "\n\nwith line numbers: " +
                hits.joinToString { "${it.first}:${it.second}" },
            listOf("LocalSource.kt: override val id: String = $literal"),
            hits.map { "${it.first}: ${it.third}" },
        )
    }
}
