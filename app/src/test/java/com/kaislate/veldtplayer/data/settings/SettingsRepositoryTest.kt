// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.util.Constants as PillConstants
import com.kaislate.veldtplayer.pill.util.IslandPosition
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

    /**
     * Finding 10: the launch window reads [ThemeModeMirror], so a choice that reached only
     * DataStore would be drawn wrong on the next cold start. Each mode is asserted in BOTH stores,
     * by value, and from a different starting mode each time, so a write that happened to leave
     * the mirror on its previous value cannot pass.
     */
    @Test fun `setting the theme writes DataStore and the launch mirror`() = runTest {
        val mirror = ThemeModeMirror(ApplicationProvider.getApplicationContext())
        val written = listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM, ThemeMode.DARK)
        val readBack = written.map { mode ->
            repo.setThemeMode(mode)
            Triple(mode, repo.readRawForTest("theme_mode"), mirror.read())
        }
        assertEquals(written.map { Triple(it, it.name, it) }, readBack)
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
     *
     * **The KEY string is named here too**, rather than read back through the repository's own
     * private constant. Reading through the constant makes a rename invisible — write and read move
     * together and every test stays green — while on an installed device the same rename resets the
     * setting for every user on upgrade. Both halves of the on-disk contract are literals here
     * because both are what an already-shipped install depends on.
     */
    @Test fun `the folder sort is stored under its own key, by NAME and not ordinal`() = runTest {
        repo.setFolderSort(TrackSort.DATE_MODIFIED)
        assertEquals(
            "the folder sort is not on disk under its own name — an ordinal, or the wrong key",
            "DATE_MODIFIED",
            repo.readRawForTest("folder_sort"),
        )
    }

    @Test fun `an unrecognised stored folder sort degrades to the default`() = runTest {
        repo.writeRawFolderSortForTest("NOT_A_SORT")
        assertEquals(TrackSort.FILENAME, repo.folderSort.first())
    }

    /**
     * Two keys, not one: setting a direction must not disturb the sort, or vice versa.
     *
     * The raw read is here for the same reason as in the test above — it is the only assertion that
     * a rename of `folder_sort_desc` would break, since reading through the constant renames with it.
     */
    @Test fun `the descending flag round-trips independently of the sort`() = runTest {
        repo.setFolderSort(TrackSort.TITLE)
        repo.setFolderSortDescending(true)
        assertEquals(
            "the sort and its direction are not independently stored, or the direction's key moved",
            listOf<Any?>(TrackSort.TITLE, true, true),
            listOf<Any?>(
                repo.folderSort.first(),
                repo.folderSortDescending.first(),
                repo.readRawBooleanForTest("folder_sort_desc"),
            ),
        )
    }

    // ---- Metered bitrate cap (N2 Task 4). Int on the wire, not a name: the values ARE numbers. ----

    @Test fun `the metered cap defaults to original quality`() = runTest {
        assertEquals(0, repo.meteredMaxBitRate.first())
    }

    /** Every allowed value round-trips, read back through the flow AND off disk under the literal
     *  key — the raw read is what a key rename or a String wire format would break. */
    @Test fun `each allowed cap is stored as an Int under metered_max_bitrate`() = runTest {
        val readBack = listOf(0, 320, 192, 128).map { kbps ->
            repo.setMeteredMaxBitRate(kbps)
            Triple(kbps, repo.meteredMaxBitRate.first(), repo.readRawIntForTest("metered_max_bitrate"))
        }
        assertEquals(
            "the cap is not on disk as an Int under its own key, or does not read back",
            listOf(Triple(0, 0, 0), Triple(320, 320, 320), Triple(192, 192, 192), Triple(128, 128, 128)),
            readBack,
        )
    }

    /** A value outside {0,320,192,128} — a downgrade, or a hand-edited store — reads as original,
     *  never as a cap nobody chose. 999 is above every allowed value, so a clamp would not hide it. */
    @Test fun `an unrecognised stored cap reads as original quality`() = runTest {
        repo.writeRawMeteredMaxBitRateForTest(999)
        assertEquals(
            listOf<Int?>(999, 0),
            listOf(repo.readRawIntForTest("metered_max_bitrate"), repo.meteredMaxBitRate.first()),
        )
    }

    // ---- LRCLIB opt-in (P1.5b Task 3). Plain Boolean, off by default (spec §8). ----

    @Test fun `online lyrics default to off`() = runTest {
        assertEquals(false, repo.lyricsOnline.first())
    }

    /**
     * The raw read pins BOTH halves of the on-disk contract, the same way the folder-sort and
     * metered-cap tests above do: the KEY string `lyrics_online` (a rename here silently resets
     * every installed user's opt-in on upgrade, invisible to a test that reads back only through
     * [SettingsRepository.lyricsOnline]) and the Boolean WIRE FORMAT (so a future rewrite as a
     * String or an Int is caught here rather than by a UI symptom).
     */
    @Test fun `online lyrics round-trip as a Boolean under lyrics_online`() = runTest {
        repo.setLyricsOnline(true)
        assertEquals(
            "online lyrics are not on disk as a Boolean under their own key, or do not read back",
            listOf<Any?>(true, true),
            listOf<Any?>(repo.lyricsOnline.first(), repo.readRawBooleanForTest("lyrics_online")),
        )
    }

    @Test fun `online lyrics can be turned back off`() = runTest {
        repo.setLyricsOnline(true)
        repo.setLyricsOnline(false)
        assertEquals(false, repo.lyricsOnline.first())
    }

    // ---- Tag scan generation (API 29 legacy-storage fix). Plain Int, defaults to 0. ----

    @Test fun `the tag scan generation defaults to 0`() = runTest {
        assertEquals(0, repo.tagScanGeneration.first())
    }

    /**
     * The raw read pins both halves of the on-disk contract, same reasoning as the metered-cap
     * and lyrics-online tests above: the KEY string `tag_scan_generation` (a rename would make
     * `LibraryScanWorker` treat every install as generation 0 again, silently forcing a re-tag on
     * every scan forever) and the Int WIRE FORMAT.
     */
    @Test fun `the tag scan generation round-trips as an Int under tag_scan_generation`() = runTest {
        repo.setTagScanGeneration(2)
        assertEquals(
            "the tag scan generation is not on disk as an Int under its own key, or does not read back",
            listOf<Any?>(2, 2),
            listOf<Any?>(repo.tagScanGeneration.first(), repo.readRawIntForTest("tag_scan_generation")),
        )
    }

    // ---- Built-in pill (P1.5c Task 2). Keys and defaults per spec §4. ----

    @Test fun `the pill mode defaults to built-in`() = runTest {
        assertEquals(PillMode.BUILT_IN, repo.pillMode.first())
    }

    @Test fun `each pill mode round-trips under its own name`() = runTest {
        val readBack = PillMode.entries.map { mode ->
            repo.setPillMode(mode); mode to repo.pillMode.first()
        }
        assertEquals(PillMode.entries.map { it to it }, readBack)
    }

    /** Stored by NAME, not ordinal -- same reasoning as [themeMode]'s test above: an ordinal
     *  ties the stored value to declaration order, so inserting a PillMode constant would
     *  silently rewrite every installed user's setting on upgrade. */
    @Test fun `an unrecognised stored pill mode degrades to built-in`() = runTest {
        repo.writeRawPillModeForTest("NOT_A_MODE")
        assertEquals(PillMode.BUILT_IN, repo.pillMode.first())
    }

    @Test fun `pill mode is stored under pill_mode`() = runTest {
        repo.setPillMode(PillMode.OFF)
        assertEquals(
            "the pill mode is not on disk under its own key, or not by NAME",
            "OFF",
            repo.readRawForTest("pill_mode"),
        )
    }

    @Test fun `force built-in defaults to off`() = runTest {
        assertEquals(false, repo.pillForceBuiltIn.first())
    }

    @Test fun `force built-in round-trips as a Boolean under pill_force_builtin`() = runTest {
        repo.setPillForceBuiltIn(true)
        assertEquals(
            listOf<Any?>(true, true),
            listOf<Any?>(repo.pillForceBuiltIn.first(), repo.readRawBooleanForTest("pill_force_builtin")),
        )
    }

    @Test fun `the pill anchor defaults to top-center`() = runTest {
        assertEquals(IslandPosition.TOP_CENTER, repo.pillAnchor.first())
    }

    @Test fun `each pill anchor round-trips under its own key string`() = runTest {
        val readBack = IslandPosition.entries.map { position ->
            repo.setPillAnchor(position); position to repo.pillAnchor.first()
        }
        assertEquals(IslandPosition.entries.map { it to it }, readBack)
    }

    /**
     * Stored by [IslandPosition.key] (`"top-center"`), not the enum's Kotlin name
     * (`"TOP_CENTER"`) -- the wire format is pinned on the literal string, which reading back
     * only through [SettingsRepository.pillAnchor] cannot tell apart from a name-based scheme.
     */
    @Test fun `the pill anchor is stored under pill_anchor by its key string`() = runTest {
        repo.setPillAnchor(IslandPosition.BOTTOM_RIGHT)
        assertEquals(
            "the pill anchor is not on disk under its own key, or not by IslandPosition.key",
            "bottom-right",
            repo.readRawForTest("pill_anchor"),
        )
    }

    @Test fun `an unrecognised stored pill anchor degrades to top-center`() = runTest {
        repo.writeRawPillAnchorForTest("diagonally")
        assertEquals(IslandPosition.TOP_CENTER, repo.pillAnchor.first())
    }

    @Test fun `the pill width defaults to 160 dp`() = runTest {
        assertEquals(160, repo.pillWidthDp.first())
    }

    @Test fun `the pill width round-trips as an Int under pill_width`() = runTest {
        repo.setPillWidthDp(220)
        assertEquals(
            listOf<Any?>(220, 220),
            listOf<Any?>(repo.pillWidthDp.first(), repo.readRawIntForTest("pill_width")),
        )
    }

    @Test fun `the pill wave style defaults to wisptrail`() = runTest {
        assertEquals("wisptrail", repo.pillWaveStyle.first())
    }

    @Test fun `the pill wave style round-trips under pill_wave_style, unvalidated`() = runTest {
        repo.setPillWaveStyle("hills")
        assertEquals(
            listOf<Any?>("hills", "hills"),
            listOf<Any?>(repo.pillWaveStyle.first(), repo.readRawForTest("pill_wave_style")),
        )
    }

    @Test fun `the pill wave color defaults to accent-light`() = runTest {
        assertEquals("accent-light", repo.pillWaveColor.first())
    }

    @Test fun `the pill wave color round-trips under pill_wave_color, unvalidated`() = runTest {
        repo.setPillWaveColor("white")
        assertEquals(
            listOf<Any?>("white", "white"),
            listOf<Any?>(repo.pillWaveColor.first(), repo.readRawForTest("pill_wave_color")),
        )
    }

    @Test fun `pill art crossfade defaults to on`() = runTest {
        assertEquals(true, repo.pillArtCrossfade.first())
    }

    @Test fun `pill art crossfade round-trips as a Boolean under pill_art_crossfade`() = runTest {
        repo.setPillArtCrossfade(false)
        assertEquals(
            listOf<Any?>(false, false),
            listOf<Any?>(repo.pillArtCrossfade.first(), repo.readRawBooleanForTest("pill_art_crossfade")),
        )
    }

    /** Shares its default with the ported state machine's built-in timeout (Task 1's
     *  Constants.INACTIVITY_TIMEOUT_MS) -- the same cross-check Wisp's own
     *  SettingsDefaultsTest pinned, so the stored default and the timer cannot drift apart. */
    @Test fun `the pill hide delay defaults to the state machine's own timeout`() = runTest {
        assertEquals(PillConstants.INACTIVITY_TIMEOUT_MS, repo.pillHideDelayMs.first())
        assertEquals(25_000L, repo.pillHideDelayMs.first())
    }

    @Test fun `the pill hide delay round-trips as a Long under pill_hide_delay_ms`() = runTest {
        repo.setPillHideDelayMs(5_000L)
        assertEquals(
            listOf<Any?>(5_000L, 5_000L),
            listOf<Any?>(repo.pillHideDelayMs.first(), repo.readRawLongForTest("pill_hide_delay_ms")),
        )
    }

    @Test fun `pill transport buttons default to off`() = runTest {
        assertEquals("off", repo.pillTransportButtons.first())
    }

    @Test fun `pill transport buttons round-trip under pill_transport_buttons, unvalidated`() = runTest {
        repo.setPillTransportButtons("prev-play-next")
        assertEquals(
            listOf<Any?>("prev-play-next", "prev-play-next"),
            listOf<Any?>(repo.pillTransportButtons.first(), repo.readRawForTest("pill_transport_buttons")),
        )
    }

    // ---- Hidden folders (Step 5 spec §4) ----

    @Test fun `no folder is hidden by default`() = runTest {
        assertEquals(emptySet<String>(), repo.excludedFolders.first())
    }

    /**
     * Asserted on the RAW set under the literal key `excluded_folders`, for the reason the folder
     * sort's key test records: a key renamed on both sides stays green through the flow and un-hides
     * every user's folders on upgrade. The keys themselves are stored verbatim — a folder key is
     * byte-exact and a normalising write would hide a different folder.
     */
    @Test fun `hidden folders are stored as a set under their own key, and showing one removes it`() =
        runTest {
            repo.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = true)
            repo.setFolderHidden("external_primary:Music/ A", hidden = true)
            val both = repo.readRawStringSetForTest("excluded_folders")
            repo.setFolderHidden("1234-5678:BACKUP/Downloads", hidden = false)
            val one = repo.readRawStringSetForTest("excluded_folders")
            repo.setFolderHidden("external_primary:Music/ A", hidden = false)
            assertEquals(
                listOf(
                    setOf("1234-5678:BACKUP/Downloads", "external_primary:Music/ A"),
                    setOf("external_primary:Music/ A"),
                    null,
                    emptySet<String>(),
                ),
                listOf(both, one, repo.readRawStringSetForTest("excluded_folders"), repo.excludedFolders.first()),
            )
        }

    /**
     * The synthetic keys are not folders and hiding one is a caller bug. Showing is never refused,
     * though: a stored key this build cannot read still has to be removable from Settings.
     */
    @Test fun `a non-folder key cannot be hidden, but any stored key can be shown`() = runTest {
        val refused = runCatching { repo.setFolderHidden("\u0000unfiled", hidden = true) }
        repo.setFolderHidden("\u0000unfiled", hidden = false)
        assertEquals(
            listOf<Any?>(IllegalArgumentException::class.java, emptySet<String>()),
            listOf<Any?>(refused.exceptionOrNull()?.javaClass, repo.excludedFolders.first()),
        )
    }

    // ---- Volume names (Step 5 spec §5) ----

    @Test fun `no volume is named by default`() = runTest {
        assertEquals(emptyMap<String, String>(), repo.volumeNames.first())
    }

    /**
     * The wire format, asserted RAW under the literal key `volume_names`: one JSON object, names
     * trimmed, and a second volume's name added beside the first rather than replacing it. Then a
     * blank name resets ONE volume and leaves the other, and resetting the last removes the key.
     */
    @Test fun `names are stored as one JSON object under their own key, trimmed, per volume`() =
        runTest {
            repo.setVolumeName("1234-5678", "  Band card ")
            repo.setVolumeName("external_primary", "Phone")
            val both = repo.readRawForTest("volume_names")
            repo.setVolumeName("external_primary", "   ")
            val one = repo.readRawForTest("volume_names")
            val oneMap = repo.volumeNames.first()
            repo.setVolumeName("1234-5678", null)
            assertEquals(
                listOf<Any?>(
                    """{"1234-5678":"Band card","external_primary":"Phone"}""",
                    """{"1234-5678":"Band card"}""",
                    mapOf("1234-5678" to "Band card"),
                    null,
                ),
                listOf<Any?>(both, one, oneMap, repo.readRawForTest("volume_names")),
            )
        }

    /** 40 characters is allowed; 41 is a caller bug and writes nothing. */
    @Test fun `a name longer than forty characters is refused and not stored`() = runTest {
        val forty = "x".repeat(40)
        repo.setVolumeName("1234-5678", forty)
        val refused = runCatching { repo.setVolumeName("1234-5678", "y".repeat(41)) }
        assertEquals(
            listOf<Any?>(IllegalArgumentException::class.java, mapOf("1234-5678" to forty)),
            listOf<Any?>(refused.exceptionOrNull()?.javaClass, repo.volumeNames.first()),
        )
    }

    /** A corrupt or foreign value degrades to no names — the Folders tab must still draw. */
    @Test fun `an unreadable stored value reads as no names, and a partly readable one keeps what it can`() =
        runTest {
            repo.writeRawVolumeNamesForTest("not json")
            val garbage = repo.volumeNames.first()
            repo.writeRawVolumeNamesForTest("""["a","b"]""")
            val array = repo.volumeNames.first()
            repo.writeRawVolumeNamesForTest("""{"1234-5678":"Band card","x":3,"y":{"z":1}}""")
            val partial = repo.volumeNames.first()
            assertEquals(
                listOf(emptyMap(), emptyMap(), mapOf("1234-5678" to "Band card")),
                listOf(garbage, array, partial),
            )
        }
}
