// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.TrackSort
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: DataStore writes to a real file under the app's context.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before fun setUp() {
        repo = SettingsRepository(ApplicationProvider.getApplicationContext())
        // One DataStore is shared by every test method in this JVM (the delegate is a top-level
        // property), so writes survive between them and JUnit's method order is not specified.
        // Observed: adding the folder-sort tests turned `the default is follow-system`'s sibling
        // red, because a test that had already run left a value behind. Start from empty.
        runBlocking { repo.clearForTest() }
    }

    @Test fun `the default is follow-system`() = runTest {
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test fun `a written mode reads back`() = runTest {
        repo.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repo.themeMode.first())
    }

    /**
     * Stored by NAME, not ordinal. Reordering the enum must not silently change a user's
     * setting — with ordinals, inserting a value at the front turns everyone's DARK into
     * something else on upgrade, with no error anywhere.
     */
    @Test fun `an unrecognised stored value degrades to the default`() = runTest {
        repo.writeRawForTest("NOT_A_MODE")
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test fun `each mode round-trips under its own name`() = runTest {
        val readBack = ThemeMode.entries.map { mode ->
            repo.setThemeMode(mode); mode to repo.themeMode.first()
        }
        assertEquals(ThemeMode.entries.map { it to it }, readBack)
    }

    // ---- Folder view sort (P1.6). Same shape as the ThemeMode block above, deliberately. ----

    /** Filename, not tags — the folder view exists to bypass the tags. See `FolderSort`'s KDoc. */
    @Test fun `the folder view defaults to filename order, ascending`() = runTest {
        assertEquals(
            listOf<Any>(TrackSort.FILENAME, false),
            listOf<Any>(repo.folderSort.first(), repo.folderSortDescending.first()),
        )
    }

    @Test fun `each folder sort round-trips under its own name`() = runTest {
        val readBack = TrackSort.entries.map { sort ->
            repo.setFolderSort(sort); sort to repo.folderSort.first()
        }
        assertEquals(TrackSort.entries.map { it to it }, readBack)
    }

    /**
     * Stored by NAME, not ordinal — asserted on the STORED STRING, because reading back through
     * [SettingsRepository.folderSort] cannot tell the two apart. An ordinal keeps working right up
     * until someone reorders [TrackSort], at which point every user's setting silently becomes a
     * different sort with no error anywhere.
     *
     * The expected value is the literal `"DATE_MODIFIED"` rather than
     * `TrackSort.DATE_MODIFIED.name`: the latter would be satisfied by any implementation that
     * stores *something derived from the name*, while the literal pins the actual wire format that
     * an already-installed app has to keep reading. `DATE_MODIFIED` is chosen because it is neither
     * the default nor ordinal 0, so neither a no-op write nor an ordinal write can coincide with it.
     */
    @Test fun `the folder sort is stored by NAME, not ordinal`() = runTest {
        repo.setFolderSort(TrackSort.DATE_MODIFIED)
        assertEquals(
            "the folder sort is not on disk under its own name — an ordinal, or the wrong key",
            "DATE_MODIFIED",
            repo.readRawFolderSortForTest(),
        )
    }

    @Test fun `an unrecognised stored folder sort degrades to the default`() = runTest {
        repo.writeRawFolderSortForTest("NOT_A_SORT")
        assertEquals(TrackSort.FILENAME, repo.folderSort.first())
    }

    /** Two keys, not one: setting a direction must not disturb the sort, or vice versa. */
    @Test fun `the descending flag round-trips independently of the sort`() = runTest {
        repo.setFolderSort(TrackSort.TITLE)
        repo.setFolderSortDescending(true)
        assertEquals(
            "the sort and its direction are not independently stored",
            listOf<Any>(TrackSort.TITLE, true),
            listOf<Any>(repo.folderSort.first(), repo.folderSortDescending.first()),
        )
    }
}
