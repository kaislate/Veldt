// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import com.kaislate.veldtplayer.R
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.theme.resolveDark

/**
 * Builds the widget's `RemoteViews` from a [WidgetState] and a [WidgetArt]. Stateless: the
 * observer and the provider both call it with whatever the bus currently holds.
 *
 * **Colour follows the user's theme choice, as every Veldt surface does** ([WidgetTonePair.resolvedFor]):
 * Light or Dark is baked in outright. For System it follows night mode two ways, by API: on 31+
 * every colour is set with `setColorInt(…, notNight, night)`, so the launcher picks the variant
 * itself and the widget follows a dark-mode switch without the app running; 29–30 have no such
 * call, so the system's night mode at build time ([resolveDark], the one place the app reads
 * it) picks, and the observer rebuilds on a configuration change while the process lives.
 */
object WidgetRenderer {

    /** API 31+: one `RemoteViews` holding every layout, keyed by the size it needs; the launcher
     *  chooses as the widget is resized, with no round trip to the app. */
    @RequiresApi(Build.VERSION_CODES.S)
    fun responsive(context: Context, state: WidgetState, art: WidgetArt, mode: ThemeMode): RemoteViews =
        RemoteViews(sizeMap(context, state, art, mode))

    @RequiresApi(Build.VERSION_CODES.S)
    internal fun sizeMap(context: Context, state: WidgetState, art: WidgetArt, mode: ThemeMode): Map<SizeF, RemoteViews> =
        WidgetLayout.entries.associate { layout ->
            SizeF(layout.minWidthDp, layout.minHeightDp) to build(context, layout, state, art, mode, isNight = false)
        }

    /** API 29–30: the layout for the widget's reported size, see [layoutForOptions]. */
    fun forOptions(context: Context, options: Bundle?, state: WidgetState, art: WidgetArt, mode: ThemeMode): RemoteViews =
        build(context, layoutForOptions(options), state, art, mode, resolveDark(ThemeMode.SYSTEM, Resources.getSystem().configuration))

    /**
     * The layout for an `AppWidgetManager` options bundle. In portrait — the orientation a
     * phone's home screen is in — the widget is its MIN width by its MAX height, per
     * `OPTION_APPWIDGET_*`'s own documentation. A launcher that has not reported a size yet
     * gets [WidgetLayout.WIDE], the size the widget is placed at by default.
     */
    fun layoutForOptions(options: Bundle?): WidgetLayout {
        val width = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
        val height = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0
        if (width <= 0 || height <= 0) return WidgetLayout.WIDE
        return WidgetLayout.forSize(width.toFloat(), height.toFloat())
    }

    /**
     * [isNight] only matters below API 31, and only for [ThemeMode.SYSTEM]: it picks which of
     * the two resolved tone sets is baked in. On 31+ both go to the launcher.
     */
    fun build(
        context: Context,
        layout: WidgetLayout,
        state: WidgetState,
        art: WidgetArt,
        mode: ThemeMode,
        isNight: Boolean,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layout.layoutRes)
        val tones = (if (state is WidgetState.Track) art.tones else WidgetArt.NONE.tones).resolvedFor(mode)
        views.color(R.id.widget_ground, "setColorFilter", tones, isNight) { it.ground }

        when (state) {
            WidgetState.Empty -> {
                views.setImageViewResource(R.id.widget_art, R.drawable.widget_empty_art)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.color(R.id.widget_empty, "setTextColor", tones, isNight) { it.title }
                views.setViewVisibility(R.id.widget_play_pause, View.GONE)
                if (layout.showsText) views.setViewVisibility(R.id.widget_text, View.GONE)
                if (layout.showsSkip) {
                    views.setViewVisibility(R.id.widget_prev, View.GONE)
                    views.setViewVisibility(R.id.widget_next, View.GONE)
                }
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openApp(context))
            }
            is WidgetState.Track -> {
                if (art.bitmap != null) {
                    views.setImageViewBitmap(R.id.widget_art, art.bitmap)
                } else {
                    views.setImageViewResource(R.id.widget_art, R.drawable.widget_empty_art)
                }
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                views.setViewVisibility(R.id.widget_play_pause, View.VISIBLE)
                views.setImageViewResource(
                    R.id.widget_play_pause,
                    if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                )
                views.setContentDescription(
                    R.id.widget_play_pause,
                    context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
                )
                views.color(R.id.widget_play_pause, "setColorFilter", tones, isNight) { it.playPause }
                views.setOnClickPendingIntent(R.id.widget_play_pause, WidgetIntents.playPause(context, state.isPlaying))

                if (layout.showsText) {
                    views.setViewVisibility(R.id.widget_text, View.VISIBLE)
                    views.setTextViewText(R.id.widget_title, state.title)
                    views.setTextViewText(R.id.widget_artist, state.artist)
                    views.color(R.id.widget_title, "setTextColor", tones, isNight) { it.title }
                    views.color(R.id.widget_artist, "setTextColor", tones, isNight) { it.subtitle }
                }
                if (layout.showsSkip) {
                    views.setViewVisibility(R.id.widget_prev, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_next, View.VISIBLE)
                    views.color(R.id.widget_prev, "setColorFilter", tones, isNight) { it.skip }
                    views.color(R.id.widget_next, "setColorFilter", tones, isNight) { it.skip }
                    views.setOnClickPendingIntent(R.id.widget_prev, WidgetIntents.previous(context))
                    views.setOnClickPendingIntent(R.id.widget_next, WidgetIntents.next(context))
                }
                // The whole card, so the art, the text and the padding around them all open now
                // playing; the buttons above keep their own intents, since a view's own click
                // wins over its parent's.
                views.setOnClickPendingIntent(R.id.widget_root, WidgetIntents.openNowPlaying(context))
            }
        }
        return views
    }

    private inline fun RemoteViews.color(
        viewId: Int,
        method: String,
        tones: WidgetTonePair,
        isNight: Boolean,
        role: (WidgetTones) -> Int,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setColorInt(viewId, method, role(tones.light), role(tones.dark))
        } else {
            setInt(viewId, method, role(if (isNight) tones.dark else tones.light))
        }
    }
}
