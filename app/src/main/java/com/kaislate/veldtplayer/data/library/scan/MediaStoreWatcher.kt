// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** READ_MEDIA_AUDIO on API 33+, READ_EXTERNAL_STORAGE on <=32 — mirrors `ui/nav/PermissionGate`. */
private val audioPermission: String
    get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

/**
 * Keeps the library live: watches `MediaStore.Audio.Media` and re-runs the existing
 * [LibraryScanWorker] when it changes, so copying an album onto the device updates the app
 * without the user finding the rescan action.
 *
 * Three properties matter here, and each one is a bug if it is missing:
 *
 * 1. **Debounced.** MediaStore fires a BURST — roughly one notification per file the media
 *    scanner touches — so a naive observer starts dozens of scans for one album. The coalescing
 *    is [ScanTrigger.coalesce], deliberately a pure function on a [Flow] rather than a field on
 *    this class, because this class cannot be driven from a JVM test and that policy can.
 * 2. **Single-flight.** The enqueue goes through [LibraryScanWorker.enqueue], which is unique
 *    work under `ExistingWorkPolicy.KEEP`. That is what stops a burst *outliving* the 2s window
 *    from starting a second scan across the same rows. `KEEP`, not `REPLACE`: `REPLACE` cancels
 *    the running scan and starts a new one, so under a steady trickle of notifications the scan
 *    is restarted forever and never finishes. Pinned by `ScanSingleFlightTest`.
 * 3. **Permission-gated.** `registerContentObserver` on the audio collection throws on some OEM
 *    builds when the audio-read permission is absent, so registration is conditional on it and
 *    the registration itself is additionally wrapped — a watcher that cannot start must degrade
 *    to "no live updates", never to a crash on launch.
 *
 * ### Why [sync] exists as well as [start]/[stop]
 *
 * The permission can change while this process is alive, in both directions, and neither
 * direction goes through an in-app dialog: the user can grant or revoke in Settings, and the
 * system can revoke it for an unused app. So the caller re-states the *condition* on every
 * resume ([sync]) rather than firing once on a transition it may have missed. It mirrors — and
 * is driven by the same lifecycle event as — the `ON_RESUME` re-check in `ui/nav/PermissionGate`.
 *
 * All three entry points are idempotent and `@Synchronized`: the resume callback and a future
 * caller on another thread must not be able to leave two observers registered, which would
 * double every notification.
 */
class MediaStoreWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val windowMs: Long = ScanTrigger.DEFAULT_WINDOW_MS,
) {

    private var job: Job? = null

    /** True when the audio-read permission is currently held. */
    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Register (if the permission allows it) and start scanning on change. A no-op if already
     * watching, and a no-op — NOT a throw — if the permission is absent.
     */
    @Synchronized
    fun start() {
        if (job != null) return
        if (!hasAudioPermission()) {
            Log.i(TAG, "not watching MediaStore: audio-read permission not granted")
            return
        }
        job = scope.launch {
            ScanTrigger.coalesce(changes(), windowMs).collect {
                LibraryScanWorker.enqueue(context)
            }
        }
    }

    /** Unregister and stop scanning on change. A no-op if not watching. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Bring registration in line with the permission as it is *right now* — register on grant,
     * unregister on revoke. Safe to call on every resume; see the class KDoc.
     */
    @Synchronized
    fun sync() {
        if (hasAudioPermission()) start() else stop()
    }

    /**
     * The raw notification stream. `callbackFlow` rather than a `MutableSharedFlow` fed from an
     * observer field, so that registration and unregistration are tied to the collector's
     * lifetime by construction: there is no window in which the observer is registered and
     * nothing is listening, and cancelling [job] cannot leave the observer behind.
     *
     * `notifyForDescendants = true` is required, not decorative. The registered URI is the
     * *collection*, while MediaStore notifies on individual item URIs (`…/audio/media/1234`);
     * without descendants the observer would only ever see a whole-collection notification and
     * would miss every per-file change, which is all of them.
     *
     * [Channel.CONFLATED] because only the fact of a change is carried — the payload is a
     * timestamp used for nothing but distinguishing events — so an unread notification may be
     * overwritten by a newer one with no loss of meaning. It also means a burst arriving faster
     * than the collector can drain cannot build a backlog that outlives the burst.
     */
    private fun changes(): Flow<Long> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange(selfChange, null)
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                trySend(SystemClock.uptimeMillis())
            }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(WATCHED_URI, true, observer)
        }.onFailure {
            // Some OEM providers raise instead of returning; degrade to "no live updates".
            Log.w(TAG, "could not observe MediaStore; live library updates are off", it)
        }.isSuccess

        awaitClose {
            if (registered) {
                runCatching { context.contentResolver.unregisterContentObserver(observer) }
            }
        }
    }.buffer(Channel.CONFLATED)

    companion object {
        private const val TAG = "MediaStoreWatcher"

        /**
         * The audio collection across every external volume — the same URI
         * [com.kaislate.veldtplayer.data.library.LocalSource] enumerates.
         */
        val WATCHED_URI: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
}
