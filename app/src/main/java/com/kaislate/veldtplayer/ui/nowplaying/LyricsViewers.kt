// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nowplaying

/**
 * Which surfaces currently have lyrics on screen, reduced to the one boolean
 * [LyricsStateHolder.setVisible] takes.
 *
 * **A set of viewers rather than a bare boolean, because two surfaces overlap in time.** The
 * in-place pane on now-playing and the full-screen `lyrics` route are both composed during the
 * navigation between them, and navigation disposes the LEAVING entry only after the ENTERING one
 * has composed and run its effects. With a single `setVisible(Boolean)` the last writer wins, and
 * the last writer is always the surface that is going away: popping the full-screen route back to
 * a now-playing that still shows its pane would end with the leaving route's `false` — lyrics on
 * screen, state `Hidden`, nothing resolving. Counting viewers makes the answer independent of the
 * order the two effects happen to run in: lyrics are visible while ANY viewer holds them.
 *
 * [onChange] fires only on the edges (first viewer in, last viewer out). Main-thread only, like
 * the Compose effects that drive it.
 */
class LyricsViewers(private val onChange: (Boolean) -> Unit) {
    private val viewers = mutableSetOf<Any>()

    fun set(viewer: Any, visible: Boolean) {
        val wasVisible = viewers.isNotEmpty()
        if (visible) viewers.add(viewer) else viewers.remove(viewer)
        val isVisible = viewers.isNotEmpty()
        if (isVisible != wasVisible) onChange(isVisible)
    }
}
