// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

/**
 * The index of the line that should be highlighted at [positionMs]: the last line whose
 * [LyricLine.timeMs] is `<= positionMs`, or `-1` when playback has not reached the first line
 * yet. [lines] must already be sorted by [LyricLine.timeMs] — as [LrcParser] always returns
 * them — so this does no sorting of its own.
 */
fun activeLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    var result = -1
    for (i in lines.indices) {
        if (lines[i].timeMs <= positionMs) result = i else break
    }
    return result
}
