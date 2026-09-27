// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later
// Stash notification ported from Veldt Wisp (services/IslandForegroundService.kt,
// buildStashNotification / ACTION_UNSTASH), GPL-3.0-or-later, same author.

package com.kaislate.veldtplayer.pill

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kaislate.veldtplayer.R
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped
import javax.inject.Inject

/**
 * The "the pill is hidden — tap to bring it back" notice shown while the user has stashed the
 * pill. A seam so [PillController]'s stash/unstash decisions are testable without a
 * notification manager.
 */
interface StashNotice {
    /** Posts the notice; [onTap] runs (main thread) when the user taps it. */
    fun show(onTap: () -> Unit)

    /** Removes the notice and stops listening for its tap. Idempotent. */
    fun cancel()
}

/**
 * The real [StashNotice], as Wisp did it: a plain, auto-cancelling notification whose tap
 * un-stashes. Wisp routed the tap to its own foreground service; Veldt has no pill service, so
 * the tap is a broadcast to a receiver registered here for exactly as long as the notice is up
 * (`RECEIVER_NOT_EXPORTED` + an explicit package: only Veldt's own PendingIntent can reach it).
 *
 * Without POST_NOTIFICATIONS (Android 13+) nothing can be posted; the notice is then silently
 * absent, and [PillController]'s other un-stash (Veldt coming to the foreground) is the way back.
 */
@ServiceScoped
class NotificationStashNotice @Inject constructor(
    @ApplicationContext private val context: Context,
) : StashNotice {

    private var receiver: BroadcastReceiver? = null

    override fun show(onTap: () -> Unit) {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = onTap()
            }
            ContextCompat.registerReceiver(
                context, r, IntentFilter(ACTION_UNSTASH), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = r
        }
        if (!canPost()) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Floating pill")
                .setDescription("Shown while the floating pill is hidden, to bring it back.")
                .build(),
        )
        val tap = PendingIntent.getBroadcast(
            context,
            REQUEST_UNSTASH,
            Intent(ACTION_UNSTASH).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pill)
            .setContentTitle("Veldt's pill is hidden")
            .setContentText("Tap to bring the pill back")
            .setContentIntent(tap)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post: the notice is cosmetic.
        }
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_UNSTASH = "com.kaislate.veldtplayer.action.PILL_UNSTASH"

        /** Distinct from Media3's playback notification (1001). */
        const val NOTIFICATION_ID = 2002

        /** Permanent: a user's per-channel settings are keyed on it. */
        const val CHANNEL_ID = "veldt_pill"

        private const val REQUEST_UNSTASH = 2
    }
}
