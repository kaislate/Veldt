// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.content.Context
import android.content.Intent
import com.kaislate.veldtplayer.MainActivity

/**
 * "Open Veldt at now-playing" — the one deep link the app has, used by the built-in pill's card
 * (tap on the art/title, spec §3).
 *
 * An intent extra rather than a navigation-component deep-link URI: there is exactly one
 * target, nothing outside the app may use it (MainActivity's exported filter is the launcher
 * one and does not see an extra it does not look for), and the nav host already knows how to
 * open now-playing — it only needs to be told when. [MainActivity] turns the extra into a
 * request counter that [VeldtNavHost] acts on.
 */
object NowPlayingDeepLink {

    const val EXTRA_OPEN_NOW_PLAYING = "com.kaislate.veldtplayer.extra.OPEN_NOW_PLAYING"

    /**
     * The launch intent. `NEW_TASK` because the pill starts it from a non-Activity context;
     * `SINGLE_TOP | CLEAR_TOP` for the same reason as the media notification's session activity
     * in `PlaybackService`: resume the task the user already has rather than stacking a second
     * MainActivity on it (an existing one receives the extra through `onNewIntent`).
     */
    fun intent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .putExtra(EXTRA_OPEN_NOW_PLAYING, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )

    /** Whether [intent] asks for now-playing. */
    fun isRequest(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true
}
