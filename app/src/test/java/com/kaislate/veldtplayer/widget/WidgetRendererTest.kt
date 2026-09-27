// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.R
import com.kaislate.veldtplayer.data.settings.ThemeMode
import com.kaislate.veldtplayer.ui.theme.ArtSeed
import com.kaislate.veldtplayer.ui.theme.Chromaticity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric, applying the real `RemoteViews` to a real view tree: which layout for which
 * size, and what the views hold in the empty and the playing state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun options(minWidth: Int, maxHeight: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, minWidth)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, maxHeight)
    }

    private fun applied(
        layout: WidgetLayout,
        state: WidgetState,
        art: WidgetArt = WidgetArt.NONE,
        mode: ThemeMode = ThemeMode.SYSTEM,
    ): View = WidgetRenderer.build(context, layout, state, art, mode, isNight = false).apply(context, FrameLayout(context))

    // ------------------------------------------------------------------------ layout by size

    @Test fun `API 29-30 - the reported options pick the layout`() {
        assertEquals(WidgetLayout.SMALL, WidgetRenderer.layoutForOptions(options(130, 102)))
        assertEquals(WidgetLayout.WIDE, WidgetRenderer.layoutForOptions(options(276, 102)))
        assertEquals(WidgetLayout.LARGE, WidgetRenderer.layoutForOptions(options(276, 220)))
    }

    @Test fun `API 29-30 - no reported size yet is the default placement, WIDE`() {
        assertEquals(WidgetLayout.WIDE, WidgetRenderer.layoutForOptions(null))
        assertEquals(WidgetLayout.WIDE, WidgetRenderer.layoutForOptions(Bundle()))
    }

    @Test fun `API 31+ - the size map keys each layout at its own minimum`() {
        val map = WidgetRenderer.sizeMap(context, WidgetState.Empty, WidgetArt.NONE, ThemeMode.SYSTEM)
        assertEquals(
            mapOf(
                SizeF(0f, 0f) to R.layout.widget_now_playing_small,
                SizeF(180f, 0f) to R.layout.widget_now_playing_wide,
                SizeF(180f, 150f) to R.layout.widget_now_playing_large,
            ),
            map.mapValues { it.value.layoutId },
        )
        for ((size, views) in map) {
            assertEquals("$size", WidgetLayout.forSize(size.width, size.height).layoutRes, views.layoutId)
        }
    }

    // --------------------------------------------------------------------------- empty state

    @Test fun `the empty state says Tap to open Veldt and shows no controls`() {
        for (layout in WidgetLayout.entries) {
            val root = applied(layout, WidgetState.Empty)
            val empty = root.findViewById<TextView>(R.id.widget_empty)
            assertEquals("$layout", View.VISIBLE, empty.visibility)
            assertEquals("$layout", "Tap to open Veldt", empty.text.toString())
            assertEquals("$layout", View.GONE, root.findViewById<View>(R.id.widget_play_pause).visibility)
            if (layout.showsSkip) {
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_prev).visibility)
                assertEquals(View.GONE, root.findViewById<View>(R.id.widget_next).visibility)
            }
            if (layout.showsText) assertEquals(View.GONE, root.findViewById<View>(R.id.widget_text).visibility)
        }
    }

    @Test fun `the empty state is drawn in the neutral tones`() {
        val root = applied(WidgetLayout.WIDE, WidgetState.Empty)
        assertEquals(
            WidgetArt.NONE.tones.light.title,
            root.findViewById<TextView>(R.id.widget_empty).currentTextColor,
        )
    }

    // ------------------------------------------------------------------------------ a track

    @Test fun `a playing track shows its title, artist, pause and both skips, in the art's tones`() {
        val tones = WidgetTonePair.of(ArtSeed(Chromaticity(25.0, 70.0)))
        val art = WidgetArt(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888), tones)
        val root = applied(WidgetLayout.WIDE, WidgetState.Track("Rosa", "Orla Vance", isPlaying = true), art)

        val title = root.findViewById<TextView>(R.id.widget_title)
        val artist = root.findViewById<TextView>(R.id.widget_artist)
        assertEquals("Rosa", title.text.toString())
        assertEquals("Orla Vance", artist.text.toString())
        assertEquals(tones.light.title, title.currentTextColor)
        assertEquals(tones.light.subtitle, artist.currentTextColor)
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)

        val play = root.findViewById<ImageView>(R.id.widget_play_pause)
        assertEquals(View.VISIBLE, play.visibility)
        assertEquals("Pause", play.contentDescription.toString())
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_prev).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_next).visibility)
        assertNotNull(root.findViewById<ImageView>(R.id.widget_art).drawable)
    }

    @Test fun `a paused track in the small layout shows play and nothing else`() {
        val root = applied(WidgetLayout.SMALL, WidgetState.Track("Rosa", "Orla Vance", isPlaying = false))
        val play = root.findViewById<ImageView>(R.id.widget_play_pause)
        assertEquals(View.VISIBLE, play.visibility)
        assertEquals("Play", play.contentDescription.toString())
        assertEquals(View.GONE, root.findViewById<View>(R.id.widget_empty).visibility)
    }

    @Test fun `an explicit Dark choice is drawn dark even where the system is light`() {
        val tones = WidgetTonePair.of(ArtSeed(Chromaticity(260.0, 50.0)))
        val art = WidgetArt(null, tones)
        val root = applied(WidgetLayout.WIDE, WidgetState.Track("t", "a", isPlaying = false), art, ThemeMode.DARK)
        assertEquals(tones.dark.title, root.findViewById<TextView>(R.id.widget_title).currentTextColor)
        assertEquals(tones.dark.subtitle, root.findViewById<TextView>(R.id.widget_artist).currentTextColor)
    }
}
