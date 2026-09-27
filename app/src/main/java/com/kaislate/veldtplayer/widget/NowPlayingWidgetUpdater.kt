// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.MediaMetadata
import android.os.Build
import com.kaislate.veldtplayer.data.media.MediaSessionBus
import com.kaislate.veldtplayer.data.settings.ThemeModeMirror
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Keeps every placed now-playing widget in step with [MediaSessionBus] (spec 0.9.2 §7): a push
 * on a track change, a play-state change or a new cover, and never on a timer
 * (`updatePeriodMillis="0"`).
 *
 * **Why the bus, observed from the process, and not the session or the pill.** The bus is what
 * `PlayerBusAdapter` already publishes from inside the playback service — title, artist, play
 * state and the cover decoded by the notification's own bitmap loader — so the widget shows
 * exactly what the notification and the pill show, and needs no change to the service and no
 * `MediaController` connection of its own. The pill's collectors live in its overlay's
 * composition, which exists only while the pill is enabled; the widget must update whether or
 * not the pill is on, so it has its own collector for the life of the process.
 *
 * **Started only when a widget exists.** [ensureRunning] is called from `VeldtApp.onCreate` and
 * from the provider's `onEnabled`/`onUpdate`, and does nothing while no widget is placed — so an
 * install without the widget, and every Robolectric test (each builds a fresh `VeldtApp`), costs
 * one `getAppWidgetIds` and no collector. [stop] runs from `onDisabled`, when the last one goes.
 *
 * **Theme.** The user's Light/Dark/System choice is read at each render from [ThemeModeMirror],
 * the synchronous copy `SettingsRepository` writes on every change, and a change to that file
 * re-renders — so switching the theme in Settings recolours the widget at once.
 *
 * The collectors are cheap: the bus's `StateFlow`s are in-process and emit only on change, the
 * track is reduced to a [WidgetState] and deduplicated before anything is built, and the cover's
 * Palette pass and downscale ([WidgetArt.prepare]) run once per new cover, on
 * [Dispatchers.Default], cancelled if the next cover lands first.
 */
object NowPlayingWidgetUpdater {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var configCallbacks: ComponentCallbacks? = null

    /** Held here because SharedPreferences keeps its listeners only weakly. */
    private var themeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Bumped to re-render with unchanged bus state: a theme choice change, or (API 29–30) a
     *  system night-mode switch. */
    private val refresh = MutableStateFlow(0)

    @Synchronized
    fun ensureRunning(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        if (widgetIds(app).isEmpty()) return
        job = scope.launch { observe(app) }
        if (themeListener == null) {
            themeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> requestRefresh() }
                .also(themePrefs(app)::registerOnSharedPreferenceChangeListener)
        }
        // API 29–30 bake one theme into the RemoteViews (see WidgetRenderer); follow a system
        // dark-mode switch while the process lives. 31+ switches in the launcher on its own.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && configCallbacks == null) {
            configCallbacks = object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = requestRefresh()

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            }.also(app::registerComponentCallbacks)
        }
    }

    @Synchronized
    fun stop(context: Context) {
        job?.cancel()
        job = null
        configCallbacks?.let(context.applicationContext::unregisterComponentCallbacks)
        configCallbacks = null
        themeListener?.let(themePrefs(context.applicationContext)::unregisterOnSharedPreferenceChangeListener)
        themeListener = null
    }

    fun requestRefresh() = refresh.update { it + 1 }

    /**
     * Renders what the bus holds right now to every placed widget, off the main thread, then
     * calls [done]. The provider calls this inside `goAsync` so a widget just placed, resized,
     * or restored after a reboot is drawn even before — or without — the collector, and the
     * process is not let go of until it has been.
     */
    fun renderNow(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        scope.launch {
            try {
                val meta = MediaSessionBus.metadata.value
                val state = stateOf(meta, MediaSessionBus.playbackState.value)
                render(app, state, WidgetArt.prepare(MediaSessionBus.albumArt.value))
            } finally {
                done()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observe(app: Context) {
        val state = combine(MediaSessionBus.metadata, MediaSessionBus.playbackState, ::stateOf).distinctUntilChanged()
        val art = MediaSessionBus.albumArt.mapLatest(WidgetArt::prepare)
        combine(state, art, refresh) { s, a, _ -> s to a }
            .collect { (s, a) -> render(app, s, a) }
    }

    private fun stateOf(meta: MediaMetadata?, playbackState: Int?): WidgetState =
        WidgetState.of(
            meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
            meta?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            playbackState,
        )

    private fun render(app: Context, state: WidgetState, art: WidgetArt) {
        val manager = AppWidgetManager.getInstance(app) ?: return
        val ids = widgetIds(app)
        if (ids.isEmpty()) return
        val mode = ThemeModeMirror(app).read()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.updateAppWidget(ids, WidgetRenderer.responsive(app, state, art, mode))
        } else {
            for (id in ids) {
                manager.updateAppWidget(id, WidgetRenderer.forOptions(app, manager.getAppWidgetOptions(id), state, art, mode))
            }
        }
    }

    private fun themePrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ThemeModeMirror.FILE, Context.MODE_PRIVATE)

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, NowPlayingWidgetProvider::class.java))
            ?: IntArray(0)
}
