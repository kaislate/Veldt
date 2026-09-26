// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaislate.veldtplayer.data.library.TrackSort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Light, Dark, or whatever the system is doing. Default [SYSTEM]. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

private val Context.settingsStore by preferencesDataStore(name = "veldt-settings")

/**
 * The app's preference store. The pill's three-way toggle lands here later.
 *
 * Enum values persist by `name`, never by ordinal: an ordinal makes the stored value depend on
 * DECLARATION ORDER, so inserting an enum constant silently rewrites every user's setting on
 * upgrade with nothing to notice it. An unreadable value degrades to the default rather than
 * throwing — a corrupt preference must not stop the app from starting.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val themeMode: Flow<ThemeMode> = context.settingsStore.data.map { prefs ->
        prefs[THEME_MODE]?.let { stored ->
            ThemeMode.entries.firstOrNull { it.name == stored }
        } ?: ThemeMode.SYSTEM
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[THEME_MODE] = mode.name }
    }

    /**
     * The folder view's track order. Persisted by NAME and an unrecognised value resolves to the
     * default rather than throwing — same reasoning as [themeMode]: a stored enum outliving its
     * constant is a downgrade or a rename, not a reason to crash.
     *
     * The default is [TrackSort.FILENAME] and not a tag-derived order; see the KDoc on
     * `FolderSort` for why that is the whole point of this view.
     */
    val folderSort: Flow<TrackSort> = context.settingsStore.data.map { prefs ->
        prefs[FOLDER_SORT]?.let { stored ->
            TrackSort.entries.firstOrNull { it.name == stored }
        } ?: TrackSort.FILENAME
    }

    suspend fun setFolderSort(sort: TrackSort) {
        context.settingsStore.edit { it[FOLDER_SORT] = sort.name }
    }

    val folderSortDescending: Flow<Boolean> =
        context.settingsStore.data.map { it[FOLDER_SORT_DESC] ?: false }

    suspend fun setFolderSortDescending(descending: Boolean) {
        context.settingsStore.edit { it[FOLDER_SORT_DESC] = descending }
    }

    /**
     * The `maxBitRate` cap, in kbps, for streaming on a METERED network (N2 Task 4). 0 is
     * "original quality"; the only other values are [METERED_CAPS]. Unmetered networks always
     * stream original — that is the owner's decision and lives in `effectiveMaxBitRate`, not here.
     *
     * Stored as an Int rather than by name, unlike the enums above: the value IS the number sent
     * to the server, so there is no declaration order to drift. Anything outside the allowed set —
     * a downgrade, a hand-edited store — reads as 0, never as a cap nobody chose.
     */
    val meteredMaxBitRate: Flow<Int> = context.settingsStore.data.map { prefs ->
        prefs[METERED_MAX_BITRATE]?.takeIf { it in METERED_CAPS } ?: 0
    }

    /** @throws IllegalArgumentException for a value outside [METERED_CAPS] — a caller bug. */
    suspend fun setMeteredMaxBitRate(kbps: Int) {
        require(kbps in METERED_CAPS) { "unsupported metered bitrate cap: $kbps" }
        context.settingsStore.edit { it[METERED_MAX_BITRATE] = kbps }
    }

    /**
     * Test seam: empties the store, so a test can observe DEFAULT resolution rather than whatever
     * an earlier test left behind.
     *
     * The [preferencesDataStore] delegate is a top-level property, so a single store is shared by
     * every test method in a JVM and writes survive between them. Without this the default-value
     * tests pass only when they happen to run before the writing ones.
     */
    internal suspend fun clearForTest() {
        context.settingsStore.edit { it.clear() }
    }

    /** Test seam: writes a raw string so the unrecognised-value path is reachable. */
    internal suspend fun writeRawForTest(raw: String) {
        context.settingsStore.edit { it[THEME_MODE] = raw }
    }

    /** Test seam: as [writeRawForTest], for the folder sort's unrecognised-value path. */
    internal suspend fun writeRawFolderSortForTest(raw: String) {
        context.settingsStore.edit { it[FOLDER_SORT] = raw }
    }

    /**
     * The LRCLIB opt-in (spec §8): "Find lyrics online (LRCLIB)", off by default. Unlike
     * [themeMode] or [folderSort] this is a plain Boolean, not a name — there is no enum to
     * out-live, so [readRawBooleanForTest] alone is the wire-format seam; no separate raw-write
     * seam is needed since [setLyricsOnline] already writes the same key.
     */
    val lyricsOnline: Flow<Boolean> = context.settingsStore.data.map { it[LYRICS_ONLINE] ?: false }

    suspend fun setLyricsOnline(enabled: Boolean) {
        context.settingsStore.edit { it[LYRICS_ONLINE] = enabled }
    }

    /**
     * Test seam: the stored value as stored, under a key the CALLER names.
     *
     * Two things depend on it being the caller's key string rather than the constant above. It pins
     * the **wire format** — reading back through [folderSort] cannot see the difference, because it
     * resolves both a name and a stray ordinal to *some* [TrackSort], so a round trip stays green
     * under a symmetric ordinal implementation that writes `ordinal` and reads it back by index.
     * And it pins the **key string**, which reading through the same private constant cannot: a
     * rename would move both the write and the read together, keep every test green, and silently
     * reset the sort of every installed user on upgrade.
     */
    internal suspend fun readRawForTest(key: String): String? =
        context.settingsStore.data.map { it[stringPreferencesKey(key)] }.first()

    /** Test seam: writes a raw Int under the metered-cap key, so the out-of-set path is reachable. */
    internal suspend fun writeRawMeteredMaxBitRateForTest(raw: Int) {
        context.settingsStore.edit { it[METERED_MAX_BITRATE] = raw }
    }

    /** As [readRawForTest], for an Int preference — pins the metered cap's key and wire format. */
    internal suspend fun readRawIntForTest(key: String): Int? =
        context.settingsStore.data.map { it[intPreferencesKey(key)] }.first()

    /** As [readRawForTest], for a boolean preference. */
    internal suspend fun readRawBooleanForTest(key: String): Boolean? =
        context.settingsStore.data.map { it[booleanPreferencesKey(key)] }.first()

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FOLDER_SORT = stringPreferencesKey("folder_sort")
        val FOLDER_SORT_DESC = booleanPreferencesKey("folder_sort_desc")
        val METERED_MAX_BITRATE = intPreferencesKey("metered_max_bitrate")
        val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")

        /** Every value [meteredMaxBitRate] can hold. 0 is original quality. */
        val METERED_CAPS: Set<Int> = setOf(0, 320, 192, 128)
    }
}
