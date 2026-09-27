// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle

/**
 * The home-screen now-playing widget (spec 0.9.2 §7). A plain `RemoteViews` provider — no
 * Glance, no new dependency.
 *
 * This class only answers the launcher: what to draw when a widget is placed, restored or
 * resized. Everything after that — track and play-state changes — is pushed by
 * [NowPlayingWidgetUpdater] from the bus, which is also why `updatePeriodMillis` is 0.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        NowPlayingWidgetUpdater.ensureRunning(context)
    }

    override fun onDisabled(context: Context) {
        NowPlayingWidgetUpdater.stop(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        NowPlayingWidgetUpdater.ensureRunning(context)
        renderNow(context)
    }

    /**
     * API 29–30's only resize signal: the layout for the new size is chosen here, by
     * [WidgetRenderer.layoutForOptions]. On 31+ the launcher already switched layouts from the
     * size map; re-rendering is harmless and keeps the two paths one code path.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        renderNow(context)
    }

    /** `goAsync` so the process is kept until the render (a Palette pass on the cover) lands. */
    private fun renderNow(context: Context) {
        val pending = goAsync()
        NowPlayingWidgetUpdater.renderNow(context) { pending.finish() }
    }
}
