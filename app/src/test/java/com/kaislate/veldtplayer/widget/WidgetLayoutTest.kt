// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import com.kaislate.veldtplayer.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JVM: [WidgetLayout.forSize] is arithmetic. The sizes are Android's published widget
 * cell table, so the table below is "which layout does a real placement get", not a set of
 * arbitrary numbers.
 */
class WidgetLayoutTest {

    @Test fun `each launcher cell size gets its layout`() {
        val table = listOf(
            Triple("2x1 portrait", 130f to 102f, WidgetLayout.SMALL),
            Triple("3x1 portrait", 203f to 102f, WidgetLayout.WIDE),
            Triple("4x1 portrait", 276f to 102f, WidgetLayout.WIDE),
            Triple("5x1 portrait", 349f to 102f, WidgetLayout.WIDE),
            Triple("4x2 portrait", 276f to 220f, WidgetLayout.LARGE),
            Triple("5x2 portrait", 349f to 220f, WidgetLayout.LARGE),
            Triple("2x2 portrait", 130f to 220f, WidgetLayout.SMALL),
            Triple("2x1 landscape", 269f to 51f, WidgetLayout.WIDE),
            Triple("4x1 landscape", 555f to 51f, WidgetLayout.WIDE),
            // 117 dp cannot hold the large layout's 148 dp; it must stay one-row.
            Triple("4x2 landscape", 555f to 117f, WidgetLayout.WIDE),
            Triple("below every minimum", 0f to 0f, WidgetLayout.SMALL),
        )
        val wrong = table.filter { (_, size, expected) -> WidgetLayout.forSize(size.first, size.second) != expected }
            .map { (name, size, expected) -> "$name: expected $expected, got ${WidgetLayout.forSize(size.first, size.second)}" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test fun `the thresholds are inclusive and exact`() {
        assertEquals(WidgetLayout.SMALL, WidgetLayout.forSize(179.9f, 400f))
        assertEquals(WidgetLayout.WIDE, WidgetLayout.forSize(180f, 149.9f))
        assertEquals(WidgetLayout.LARGE, WidgetLayout.forSize(180f, 150f))
    }

    @Test fun `each layout's own minimum size maps back to it - the API 31 size map and forSize agree`() {
        for (layout in WidgetLayout.entries) {
            assertEquals(layout.name, layout, WidgetLayout.forSize(layout.minWidthDp, layout.minHeightDp))
        }
        assertEquals(listOf(0f to 0f, 180f to 0f, 180f to 150f), WidgetLayout.entries.map { it.minWidthDp to it.minHeightDp })
    }

    @Test fun `each layout names its resource and its parts`() {
        assertEquals(R.layout.widget_now_playing_small, WidgetLayout.SMALL.layoutRes)
        assertEquals(R.layout.widget_now_playing_wide, WidgetLayout.WIDE.layoutRes)
        assertEquals(R.layout.widget_now_playing_large, WidgetLayout.LARGE.layoutRes)
        assertEquals(listOf(false, true, true), WidgetLayout.entries.map { it.showsText })
        assertEquals(listOf(false, true, true), WidgetLayout.entries.map { it.showsSkip })
    }
}
