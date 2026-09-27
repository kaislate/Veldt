// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import androidx.annotation.LayoutRes
import com.kaislate.veldtplayer.R

/**
 * The three shapes the now-playing widget takes (spec 0.9.2 §7), and the ONE rule that picks
 * between them by size.
 *
 * The same thresholds serve both platform paths, which is why they live here and not in the
 * layouts: API 31+ hands the launcher a `RemoteViews(Map<SizeF, RemoteViews>)` keyed on each
 * layout's [minWidthDp]×[minHeightDp] and lets IT pick the largest that fits; API 29–30 has no
 * such map, so the provider reads the widget's reported size and calls [forSize] itself. Keying
 * the map on the very numbers [forSize] compares against is what keeps the two paths from ever
 * choosing differently.
 *
 * The numbers come from Android's cell table (portrait: 2 cells ≈ 130 dp wide, 3 ≈ 203, 4 ≈ 276;
 * one row ≈ 102 dp tall, two ≈ 220; landscape one row ≈ 51, two ≈ 117). 180 dp sits between two
 * and three cells, so a 3×1 already has room for the title and the skip buttons. 150 dp is just
 * above the large layout's own content height (148 dp, art stacked over the controls), so a
 * portrait two-row widget grows and a landscape two-row one (117 dp), which would clip it, stays
 * [WIDE]. The one-row layouts are 56 dp tall, inside even a landscape row.
 */
enum class WidgetLayout(
    @LayoutRes val layoutRes: Int,
    val minWidthDp: Float,
    val minHeightDp: Float,
    /** Whether the layout has the title/artist block. */
    val showsText: Boolean,
    /** Whether the layout has previous/next. */
    val showsSkip: Boolean,
) {
    /** 2×1: the art and play/pause. */
    SMALL(R.layout.widget_now_playing_small, 0f, 0f, showsText = false, showsSkip = false),

    /** 4×1: the art, title/artist, previous/play/next. */
    WIDE(R.layout.widget_now_playing_wide, WIDE_MIN_WIDTH_DP, 0f, showsText = true, showsSkip = true),

    /** 4×2: the same, with larger art and text. */
    LARGE(R.layout.widget_now_playing_large, WIDE_MIN_WIDTH_DP, LARGE_MIN_HEIGHT_DP, showsText = true, showsSkip = true),
    ;

    companion object {
        /**
         * The layout for a widget [widthDp] wide and [heightDp] tall. Total: any size, however
         * small, gets [SMALL] — a launcher that squeezes the widget below its declared minimum
         * still gets the layout with the fewest parts, never nothing.
         */
        fun forSize(widthDp: Float, heightDp: Float): WidgetLayout = when {
            widthDp >= WIDE_MIN_WIDTH_DP && heightDp >= LARGE_MIN_HEIGHT_DP -> LARGE
            widthDp >= WIDE_MIN_WIDTH_DP -> WIDE
            else -> SMALL
        }
    }
}

private const val WIDE_MIN_WIDTH_DP = 180f
private const val LARGE_MIN_HEIGHT_DP = 150f
