// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
 * The app's preference store. One setting today; the pill's three-way toggle and the LRCLIB
 * opt-in land here later.
 *
 * Values persist by `name`, never by ordinal: an ordinal makes the stored value depend on
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
     * Test seam: the stored string as stored, so a test can pin that it is the enum's NAME.
     *
     * Reading back through [folderSort] cannot see the difference — it resolves both a name and a
     * stray ordinal to *some* [TrackSort] — so the round trip stays green under exactly the bug
     * that reordering the enum would detonate. This is the only way to assert the wire format.
     */
    internal suspend fun readRawFolderSortForTest(): String? =
        context.settingsStore.data.map { it[FOLDER_SORT] }.first()

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FOLDER_SORT = stringPreferencesKey("folder_sort")
        val FOLDER_SORT_DESC = booleanPreferencesKey("folder_sort_desc")
    }
}
