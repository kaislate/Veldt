// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.kaislate.veldtplayer.MainActivity
import com.kaislate.veldtplayer.playback.PlaybackService
import com.kaislate.veldtplayer.ui.nav.NowPlayingDeepLink

/**
 * Every [PendingIntent] the widget carries.
 *
 * **The buttons are media-button intents to [PlaybackService]**, built the way Media3's own
 * notification builds its buttons (`DefaultActionFactory`): `ACTION_MEDIA_BUTTON`, the service
 * as the explicit component, and a `KEY_EVENT` extra. `MediaSessionService.onStartCommand`
 * already routes exactly that intent to the session's media-button handling — including on a
 * cold start, where it creates the session first — so the widget needs no receiver, no
 * controller of its own and no change to the service. It also means a press from the widget
 * is handled by the very path a press from the notification is.
 *
 * Three details follow Media3's factory for the same reasons it has them:
 *  - **Play is a foreground-service start; everything else a plain service start.** Only a
 *    press that starts playback may need to start the service from the background, and a
 *    service started that way owes a `startForeground`, which the session's playback
 *    notification provides once playback begins. Pause, previous and next only ever act on a
 *    service that is already running.
 *  - **The request code is the key code.** A `PendingIntent`'s identity ignores extras, so
 *    four intents differing only in their `KeyEvent` would otherwise collapse into one.
 *  - **Explicit PLAY / PAUSE, not PLAY_PAUSE.** The widget knows which one it is showing, and
 *    `MediaSessionImpl` holds a bare PLAY_PAUSE back to detect a double press (which it turns
 *    into "next"), so a toggle would both lag and misfire on a quick second tap.
 */
object WidgetIntents {

    /** The media-button intent for [keyCode], as [PlaybackService] receives it. */
    fun mediaButton(context: Context, keyCode: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON)
            .setComponent(ComponentName(context, PlaybackService::class.java))
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))

    /** Play when paused, pause when playing — see the class KDoc for why it is never a toggle. */
    fun playPause(context: Context, isPlaying: Boolean): PendingIntent =
        if (isPlaying) {
            service(context, KeyEvent.KEYCODE_MEDIA_PAUSE)
        } else {
            PendingIntent.getForegroundService(
                context,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY),
                FLAGS,
            )
        }

    fun previous(context: Context): PendingIntent = service(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)

    fun next(context: Context): PendingIntent = service(context, KeyEvent.KEYCODE_MEDIA_NEXT)

    /** The art and text: now playing, through the same deep link the pill's card uses. */
    fun openNowPlaying(context: Context): PendingIntent =
        PendingIntent.getActivity(context, REQUEST_NOW_PLAYING, NowPlayingDeepLink.intent(context), FLAGS)

    /**
     * The empty state: just open Veldt, the way the launcher icon does (the same MAIN/LAUNCHER
     * flags, so an existing task comes forward rather than a second one starting). Not the
     * now-playing deep link — with nothing played there is no now playing to open, and the nav
     * host would only wait for one in vain.
     */
    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            FLAGS,
        )

    private fun service(context: Context, keyCode: Int): PendingIntent =
        PendingIntent.getService(context, keyCode, mediaButton(context, keyCode), FLAGS)

    /** Immutable: nothing downstream fills anything in. UPDATE_CURRENT so a rebuilt intent
     *  replaces, rather than coexists with, the one an older build of the app registered. */
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    /** Outside the key-code range the media buttons use as request codes. */
    internal const val REQUEST_NOW_PLAYING = 1_000
    internal const val REQUEST_OPEN_APP = 1_001
}
