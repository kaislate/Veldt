// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kaislate.veldtplayer.data.library.FolderExclusion
import com.kaislate.veldtplayer.data.library.TrackSort
import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import com.kaislate.veldtplayer.data.replaygain.ReplayGainPreamp
import com.kaislate.veldtplayer.pill.PillMode
import com.kaislate.veldtplayer.pill.util.Constants as PillConstants
import com.kaislate.veldtplayer.pill.util.IslandPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Light, Dark, or whatever the system is doing. Default [SYSTEM]. */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/**
 * The bottom tabs as the user left them: [order] of tab ids, the [hidden] ones, and the [start]
 * ("Open on") id, null until chosen. Raw and unvalidated — see `ui/nav/TabArrangement` for how it
 * is read.
 */
data class StoredTabs(
    val order: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    val start: String? = null,
)

private val Context.settingsStore by preferencesDataStore(name = "veldt-settings")

/** The longest name a volume can be given (Step 5 spec §5). */
const val MAX_VOLUME_NAME = 40

/** Every entry that is a string → string pair; anything else is dropped, not guessed at. */
private fun decodeVolumeNames(raw: String?): Map<String, String> {
    val obj = raw?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        as? JsonObject ?: return emptyMap()
    return obj.mapNotNull { (volume, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { volume to it }
    }.toMap()
}

/**
 * The app's preference store, including the built-in pill's settings (P1.5c).
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

    /** The launch window's synchronous copy of [themeMode]; see [ThemeModeMirror]. */
    private val themeMirror = ThemeModeMirror(context)

    /**
     * Writes DataStore, then the mirror. In that order, so a failed DataStore write leaves the
     * mirror agreeing with the store it caches rather than with a choice that was never saved.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[THEME_MODE] = mode.name }
        themeMirror.write(mode)
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
     * The folders hidden from the library (Step 5 spec §4), as `FolderNode.key`s. See
     * [FolderExclusion] for what a key covers and why it is that key.
     *
     * A string SET, not a delimited string: DataStore stores one natively, and a folder key may
     * legally contain any delimiter a joined string would need.
     */
    val excludedFolders: Flow<Set<String>> =
        context.settingsStore.data.map { it[EXCLUDED_FOLDERS] ?: emptySet() }

    /**
     * Hide [key] from the library, or show it again. Showing the last one removes the preference
     * rather than storing an empty set, so a user who tried the feature once leaves nothing behind.
     *
     * @throws IllegalArgumentException when HIDING a key [FolderExclusion] cannot read — the
     *   Unfiled bucket or the volume chooser, neither of which is a folder. A caller bug, not user
     *   input. Showing is never refused: a stored key this build cannot read is still listed in
     *   Settings, and the user has to be able to remove it.
     */
    suspend fun setFolderHidden(key: String, hidden: Boolean) {
        require(!hidden || FolderExclusion.isFolderKey(key)) { "not a folder key: $key" }
        context.settingsStore.edit { prefs ->
            val was = prefs[EXCLUDED_FOLDERS] ?: emptySet()
            val now = if (hidden) was + key else was - key
            if (now.isEmpty()) prefs.remove(EXCLUDED_FOLDERS) else prefs[EXCLUDED_FOLDERS] = now
        }
    }

    /**
     * The user's names for storage volumes (Step 5 spec §5), MediaStore volume name → display name.
     * `VolumeNames.label` consults these first; nothing else names a volume.
     *
     * Stored as one JSON object string — the encoding this repo already reads and writes elsewhere
     * (`LrclibCache`) — rather than a set of `volume<NUL>name` pairs, which would need its own
     * escaping rules and a parser that does not yet exist. Anything unreadable reads as NO names:
     * a corrupt store must degrade to the default labels, never stop the Folders tab from drawing.
     *
     * Keyed by volume name, which for a card is its filesystem UUID, so the name follows the card
     * across remounts and survives a card that is not inserted right now.
     */
    val volumeNames: Flow<Map<String, String>> = context.settingsStore.data.map { prefs ->
        decodeVolumeNames(prefs[VOLUME_NAMES])
    }

    /**
     * Name [volume] [name], trimmed; a blank [name] (or null) removes the name, so the default
     * label returns. Removing the last name removes the preference.
     *
     * @throws IllegalArgumentException for a trimmed name longer than [MAX_VOLUME_NAME] — the
     *   rename dialog cannot produce one, so it is a caller bug.
     */
    suspend fun setVolumeName(volume: String, name: String?) {
        val trimmed = name?.trim().orEmpty()
        require(trimmed.length <= MAX_VOLUME_NAME) { "volume name longer than $MAX_VOLUME_NAME" }
        context.settingsStore.edit { prefs ->
            val was = decodeVolumeNames(prefs[VOLUME_NAMES])
            val now = if (trimmed.isEmpty()) was - volume else was + (volume to trimmed)
            if (now.isEmpty()) {
                prefs.remove(VOLUME_NAMES)
            } else {
                prefs[VOLUME_NAMES] = buildJsonObject {
                    now.forEach { (v, n) -> put(v, JsonPrimitive(n)) }
                }.toString()
            }
        }
    }

    /** Test seam: writes a raw string under the volume-names key, so the corrupt path is reachable. */
    internal suspend fun writeRawVolumeNamesForTest(raw: String) {
        context.settingsStore.edit { it[VOLUME_NAMES] = raw }
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
     * ReplayGain (0.9.2 spec §5), ON by default in [ReplayGainMode.AUTO]. Stored by NAME and
     * degrading to AUTO for anything unrecognised — [themeMode]'s convention, for its reason.
     */
    val replayGainMode: Flow<ReplayGainMode> = context.settingsStore.data.map { prefs ->
        prefs[REPLAYGAIN_MODE]?.let { raw -> ReplayGainMode.entries.firstOrNull { it.name == raw } }
            ?: ReplayGainMode.AUTO
    }

    suspend fun setReplayGainMode(mode: ReplayGainMode) {
        context.settingsStore.edit { it[REPLAYGAIN_MODE] = mode.name }
    }

    /**
     * The ReplayGain pre-amp in whole dB, −6…+6, default 0. A stored value outside the range (a
     * downgrade, a hand-edited store) is clamped into it rather than discarded: the nearest
     * setting the user could have chosen is a better guess than resetting their choice to 0.
     */
    val replayGainPreampDb: Flow<Int> = context.settingsStore.data.map { prefs ->
        (prefs[REPLAYGAIN_PREAMP_DB] ?: 0).coerceIn(ReplayGainPreamp.MIN_DB, ReplayGainPreamp.MAX_DB)
    }

    /** @throws IllegalArgumentException outside −6…+6 — a caller bug. */
    suspend fun setReplayGainPreampDb(db: Int) {
        require(db in ReplayGainPreamp.MIN_DB..ReplayGainPreamp.MAX_DB) { "pre-amp out of range: $db" }
        context.settingsStore.edit { it[REPLAYGAIN_PREAMP_DB] = db }
    }

    /** Test seam: writes a raw pre-amp so the out-of-range clamp is reachable. */
    internal suspend fun writeRawReplayGainPreampForTest(raw: Int) {
        context.settingsStore.edit { it[REPLAYGAIN_PREAMP_DB] = raw }
    }

    /** Test seam: writes a raw mode name so the unrecognised-value path is reachable. */
    internal suspend fun writeRawReplayGainModeForTest(raw: String) {
        context.settingsStore.edit { it[REPLAYGAIN_MODE] = raw }
    }

    /**
     * The tag-reading "generation" this install's library was last fully re-tagged under (API 29
     * legacy-storage fix). A plain Int, not a name — there is no enum to outlive, it only ever
     * moves forward, and the value itself has no meaning beyond "less than" / "at least" the
     * current one.
     *
     * `LibraryScanWorker` compares this to its own `CURRENT_TAG_SCAN_GENERATION`: when the stored
     * value is lower, every scanned local song is treated as changed for that one scan — re-
     * reading tags for rows `ScanDiffer` would otherwise call unchanged — and the worker advances
     * this to the current generation only after that scan succeeds. This exists because an API 29
     * library scanned before `android:requestLegacyExternalStorage` was added got MediaStore-only
     * tags for every row (file-path reads failed with `EACCES`; see `EAlvaTagReader`'s KDoc), and
     * `ScanDiffer` only re-reads tags for rows that are added or changed — an untouched row would
     * otherwise keep its MediaStore-only tags forever, even after the fix ships.
     *
     * Defaults to 0, which is below every real generation, so an install that predates this
     * feature — or a fresh one racing the DataStore default before a scan ever writes here — is
     * always treated as needing the one-time re-tag.
     */
    val tagScanGeneration: Flow<Int> =
        context.settingsStore.data.map { it[TAG_SCAN_GENERATION] ?: 0 }

    suspend fun setTagScanGeneration(generation: Int) {
        context.settingsStore.edit { it[TAG_SCAN_GENERATION] = generation }
    }

    // ---- Built-in pill (P1.5c Task 2). Keys and defaults per spec §4; defaults copied from
    // Veldt Wisp's SettingsDefaults where an equivalent setting exists there. ----

    /**
     * The "Floating pill" switch (Step 5 spec §9). Stored by NAME, degrading to
     * [PillMode.BUILT_IN] for anything unrecognised — same convention as [themeMode], and the
     * same reasoning: a corrupt or out-of-version preference must not stop the app, and
     * [PillMode.BUILT_IN] is the documented default (spec §4). The retired `USE_WISP` value
     * also reads as [PillMode.BUILT_IN]; [PillMode.fromStored] owns that decision and says why.
     */
    val pillMode: Flow<PillMode> = context.settingsStore.data.map { prefs ->
        PillMode.fromStored(prefs[PILL_MODE])
    }

    suspend fun setPillMode(mode: PillMode) {
        context.settingsStore.edit { it[PILL_MODE] = mode.name }
    }

    /**
     * The "Use Veldt's own pill instead" override (spec §3, §9): only meaningful while the
     * built-in pill would otherwise stand down for an installed Veldt Wisp. Off by default.
     */
    val pillForceBuiltIn: Flow<Boolean> =
        context.settingsStore.data.map { it[PILL_FORCE_BUILTIN] ?: false }

    suspend fun setPillForceBuiltIn(enabled: Boolean) {
        context.settingsStore.edit { it[PILL_FORCE_BUILTIN] = enabled }
    }

    /**
     * Where the pill docks on screen. Stored by [IslandPosition.key] (`"top-center"`, not the
     * enum's Kotlin NAME `"TOP_CENTER"`) — the wire format Veldt Wisp's own settings already
     * used for this, ported unchanged along with [IslandPosition] in Task 1.
     * [IslandPosition.fromKey] already degrades an unrecognised or absent key to
     * [IslandPosition.TOP_CENTER], so no separate fallback is needed here.
     */
    val pillAnchor: Flow<IslandPosition> = context.settingsStore.data.map { prefs ->
        IslandPosition.fromKey(prefs[PILL_ANCHOR])
    }

    suspend fun setPillAnchor(position: IslandPosition) {
        context.settingsStore.edit { it[PILL_ANCHOR] = position.key }
    }

    /**
     * The collapsed pill's text width budget, in dp. Wisp had two width settings — this one
     * (`PILL_TEXT_WIDTH_DP`, default 160) and a separate expanded-panel width — but spec §4
     * lists a single `pill_width` key, so that is what is ported: the pill's own width, not
     * the card's. Not validated here, same as Wisp's own repository: a slider on the settings
     * screen already restricts what the user can pick.
     */
    val pillWidthDp: Flow<Int> = context.settingsStore.data.map { it[PILL_WIDTH] ?: 160 }

    suspend fun setPillWidthDp(dp: Int) {
        context.settingsStore.edit { it[PILL_WIDTH] = dp }
    }

    /**
     * Which wave rendering the pill draws. Deliberately unvalidated, matching Wisp's own
     * repository: an unrecognised value is the drawing code's business, not the settings
     * layer's — [com.kaislate.veldtplayer.pill.ui.island.PillLayout] and its siblings already
     * fall back sanely for a string they do not recognise.
     */
    val pillWaveStyle: Flow<String> =
        context.settingsStore.data.map { it[PILL_WAVE_STYLE] ?: "wisptrail" }

    suspend fun setPillWaveStyle(style: String) {
        context.settingsStore.edit { it[PILL_WAVE_STYLE] = style }
    }

    /** Where the wave takes its colour from. Unvalidated for the same reason as [pillWaveStyle]. */
    val pillWaveColor: Flow<String> =
        context.settingsStore.data.map { it[PILL_WAVE_COLOR] ?: "accent-light" }

    suspend fun setPillWaveColor(mode: String) {
        context.settingsStore.edit { it[PILL_WAVE_COLOR] = mode }
    }

    /** Whether album art crossfades between tracks on the pill/card. On by default. */
    val pillArtCrossfade: Flow<Boolean> =
        context.settingsStore.data.map { it[PILL_ART_CROSSFADE] ?: true }

    suspend fun setPillArtCrossfade(enabled: Boolean) {
        context.settingsStore.edit { it[PILL_ART_CROSSFADE] = enabled }
    }

    /**
     * How long a paused pill lingers before hiding, in milliseconds. Defaults to
     * [PillConstants.INACTIVITY_TIMEOUT_MS] so the stored default and the ported state
     * machine's built-in timeout cannot drift apart — the same invariant Wisp's own
     * `SettingsDefaultsTest` pinned (`hide delay default is the same value the state machine
     * uses`).
     */
    val pillHideDelayMs: Flow<Long> =
        context.settingsStore.data.map { it[PILL_HIDE_DELAY_MS] ?: PillConstants.INACTIVITY_TIMEOUT_MS }

    suspend fun setPillHideDelayMs(ms: Long) {
        context.settingsStore.edit { it[PILL_HIDE_DELAY_MS] = ms }
    }

    /**
     * Which transport buttons the pill shows, or `"off"` for none. One key standing in for
     * Wisp's separate pair (`SHOW_PILL_CONTROLS` boolean + `PILL_CONTROL_SET` string), since
     * spec §4 lists a single `pill_transport_buttons` key: `"off"` is Wisp's
     * `SHOW_PILL_CONTROLS = false`, and any other value is a control-set string that
     * [com.kaislate.veldtplayer.pill.ui.island.PillLayout.buttonsFor] already knows how to
     * read (an unrecognised one there falls back to play/pause alone). Off by default,
     * matching Wisp's `SHOW_PILL_CONTROLS` default.
     */
    val pillTransportButtons: Flow<String> =
        context.settingsStore.data.map { it[PILL_TRANSPORT_BUTTONS] ?: "off" }

    suspend fun setPillTransportButtons(value: String) {
        context.settingsStore.edit { it[PILL_TRANSPORT_BUTTONS] = value }
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

    /** As [readRawForTest], for a string-set preference — pins [excludedFolders]' key. */
    internal suspend fun readRawStringSetForTest(key: String): Set<String>? =
        context.settingsStore.data.map { it[stringSetPreferencesKey(key)] }.first()

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

    /** As [readRawForTest], for a Long preference (only [pillHideDelayMs] needs one so far). */
    internal suspend fun readRawLongForTest(key: String): Long? =
        context.settingsStore.data.map { it[longPreferencesKey(key)] }.first()

    /** Test seam: as [writeRawForTest], for [pillMode]'s unrecognised-value path. */
    internal suspend fun writeRawPillModeForTest(raw: String) {
        context.settingsStore.edit { it[PILL_MODE] = raw }
    }

    /** Test seam: as [writeRawForTest], for [pillAnchor]'s unrecognised-value path. */
    internal suspend fun writeRawPillAnchorForTest(raw: String) {
        context.settingsStore.edit { it[PILL_ANCHOR] = raw }
    }

    // ---- Bottom tabs (player-sheet/server-tab spec, Round 2 → D). Raw ids only: the rules that
    // read them (unknown ids, a server tab appearing later, the start-tab fallback) live in the UI
    // layer's `TabArrangement`, as pure functions, because this layer does not know the routes.

    /**
     * The user's tab arrangement as stored: the order (comma-joined — a tab id is a route and
     * never holds a comma), the hidden set, and "Open on". All three absent on a fresh install,
     * which `TabArrangement` reads as the default order, all shown, opening on Songs.
     */
    val tabs: Flow<StoredTabs> = context.settingsStore.data.map { prefs ->
        StoredTabs(
            order = prefs[TAB_ORDER]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            hidden = prefs[TAB_HIDDEN] ?: emptySet(),
            start = prefs[START_TAB],
        )
    }

    suspend fun setTabOrder(order: List<String>) {
        context.settingsStore.edit { it[TAB_ORDER] = order.joinToString(",") }
    }

    /** An empty set removes the preference, as [setFolderHidden] does, so "all shown" leaves
     *  nothing behind. */
    suspend fun setHiddenTabs(hidden: Set<String>) {
        context.settingsStore.edit { prefs ->
            if (hidden.isEmpty()) prefs.remove(TAB_HIDDEN) else prefs[TAB_HIDDEN] = hidden
        }
    }

    suspend fun setStartTab(id: String) {
        context.settingsStore.edit { it[START_TAB] = id }
    }

    /** "Reset to default": the default order, every tab shown. "Open on" is a separate choice
     *  with its own control, and is left alone. */
    suspend fun resetTabs() {
        context.settingsStore.edit { prefs ->
            prefs.remove(TAB_ORDER)
            prefs.remove(TAB_HIDDEN)
        }
    }

    private companion object {
        val TAB_ORDER = stringPreferencesKey("tab_order")
        val TAB_HIDDEN = stringSetPreferencesKey("tab_hidden")
        val START_TAB = stringPreferencesKey("start_tab")

        val THEME_MODE = stringPreferencesKey("theme_mode")
        val FOLDER_SORT = stringPreferencesKey("folder_sort")
        val FOLDER_SORT_DESC = booleanPreferencesKey("folder_sort_desc")
        val METERED_MAX_BITRATE = intPreferencesKey("metered_max_bitrate")
        val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")
        val REPLAYGAIN_MODE = stringPreferencesKey("replaygain_mode")
        val REPLAYGAIN_PREAMP_DB = intPreferencesKey("replaygain_preamp_db")
        val TAG_SCAN_GENERATION = intPreferencesKey("tag_scan_generation")
        val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
        val VOLUME_NAMES = stringPreferencesKey("volume_names")

        /** Every value [meteredMaxBitRate] can hold. 0 is original quality. */
        val METERED_CAPS: Set<Int> = setOf(0, 320, 192, 128)

        // ---- Built-in pill (P1.5c Task 2) ----
        val PILL_MODE = stringPreferencesKey("pill_mode")
        val PILL_FORCE_BUILTIN = booleanPreferencesKey("pill_force_builtin")
        val PILL_ANCHOR = stringPreferencesKey("pill_anchor")
        val PILL_WIDTH = intPreferencesKey("pill_width")
        val PILL_WAVE_STYLE = stringPreferencesKey("pill_wave_style")
        val PILL_WAVE_COLOR = stringPreferencesKey("pill_wave_color")
        val PILL_ART_CROSSFADE = booleanPreferencesKey("pill_art_crossfade")
        val PILL_HIDE_DELAY_MS = longPreferencesKey("pill_hide_delay_ms")
        val PILL_TRANSPORT_BUTTONS = stringPreferencesKey("pill_transport_buttons")
    }
}
