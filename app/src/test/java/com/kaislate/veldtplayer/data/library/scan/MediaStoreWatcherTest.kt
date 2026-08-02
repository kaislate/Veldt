// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Permission-gating for the `ContentObserver`, asserted on the REGISTRATION ITSELF rather than on
 * a boolean the class sets. `registerContentObserver` on the audio collection throws on some OEM
 * builds when the audio-read permission is absent, so "we did not register" is the property that
 * matters, and a flag saying we did not is not the same claim.
 *
 * SDK 34 so the permission under test is `READ_MEDIA_AUDIO`, the API 33+ name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaStoreWatcherTest {

    private lateinit var app: Application
    private lateinit var scope: TestScope
    private lateinit var watcher: MediaStoreWatcher

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Unconfined so `start()`'s launch reaches the observer registration eagerly; the
        // scheduler is still virtual, so `advanceUntilIdle()` settles cancellation deterministically.
        scope = TestScope(UnconfinedTestDispatcher())
        watcher = MediaStoreWatcher(app, scope)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    /** How many scan requests exist for the unique scan name. */
    private fun scanCount(): Int =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_NAME).get().size

    /** Fire a MediaStore notification for one item under the watched collection. */
    private fun notifyItemChanged(id: Long) {
        app.contentResolver.notifyChange(
            ContentUris.withAppendedId(MediaStoreWatcher.WATCHED_URI, id),
            null,
        )
        shadowOf(Looper.getMainLooper()).idle() // the observer dispatches on a main-looper Handler
    }

    private fun observerCount(): Int =
        shadowOf(app.contentResolver).getContentObservers(MediaStoreWatcher.WATCHED_URI).size

    private fun grant() = shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
    private fun deny() = shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO)

    private fun settle() = scope.advanceUntilIdle()

    @Test
    fun `nothing is registered without the audio permission`() {
        deny()
        watcher.sync()
        settle()
        assertEquals(0, observerCount())
    }

    @Test
    fun `an explicit start without the permission also registers nothing`() {
        deny()
        watcher.start()
        settle()
        assertEquals(0, observerCount())
    }

    @Test
    fun `granting the permission registers exactly one observer`() {
        grant()
        watcher.sync()
        settle()
        assertEquals(1, observerCount())
    }

    @Test
    fun `revoking the permission unregisters the observer`() {
        grant()
        watcher.sync()
        settle()
        assertEquals(1, observerCount())

        deny()
        watcher.sync()
        settle()
        assertEquals(0, observerCount())
    }

    /**
     * [MediaStoreWatcher.sync] runs on every resume. A second registration would not fail loudly
     * — it would silently double every MediaStore notification, which the debounce would then
     * hide, so this is exactly the kind of leak that survives to a device.
     */
    @Test
    fun `repeated syncs while granted keep exactly one observer`() {
        grant()
        repeat(5) { watcher.sync(); settle() }
        assertEquals(1, observerCount())
    }

    /** A grant that follows a revoke has to register again, not stay off. */
    @Test
    fun `a re-grant after a revoke registers again`() {
        grant(); watcher.sync(); settle()
        deny(); watcher.sync(); settle()
        grant(); watcher.sync(); settle()
        assertEquals(1, observerCount())
    }

    // ---- the wiring: observer -> coalesce -> unique work ----------------------------------

    /**
     * The end-to-end path, which nothing else in this file touches. Without it the watcher could
     * observe MediaStore, never call [ScanTrigger.coalesce], and still pass every test above —
     * the debounce would be a correct pure function wired to nothing.
     *
     * The two halves are one property split in time: nothing is enqueued while notifications are
     * still arriving, and exactly one scan is enqueued once they stop.
     */
    @Test
    fun `a burst of notifications enqueues one scan, and only after the window`() {
        grant()
        watcher.start()
        settle()

        repeat(20) { notifyItemChanged(it.toLong()); scope.advanceTimeBy(50) }
        assertEquals("no scan may start while the burst is still arriving", 0, scanCount())

        scope.advanceTimeBy(ScanTrigger.DEFAULT_WINDOW_MS + 1)
        settle()
        assertEquals("the whole burst is one scan", 1, scanCount())
    }

    /** An unwatched watcher must not react — the observer really is gone after [stop]. */
    @Test
    fun `notifications after stop enqueue nothing`() {
        grant()
        watcher.start()
        settle()
        watcher.stop()
        settle()

        repeat(5) { notifyItemChanged(it.toLong()); scope.advanceTimeBy(50) }
        scope.advanceTimeBy(ScanTrigger.DEFAULT_WINDOW_MS + 1)
        settle()
        assertEquals(0, scanCount())
    }

    /** …and one that never started, because the permission was absent, must not react either. */
    @Test
    fun `notifications without the permission enqueue nothing`() {
        deny()
        watcher.sync()
        settle()

        repeat(5) { notifyItemChanged(it.toLong()); scope.advanceTimeBy(50) }
        scope.advanceTimeBy(ScanTrigger.DEFAULT_WINDOW_MS + 1)
        settle()
        assertEquals(0, scanCount())
        assertTrue(observerCount() == 0)
    }

    @Test
    fun `stop unregisters and is safe to call twice`() {
        grant()
        watcher.start()
        settle()
        assertEquals(1, observerCount())

        watcher.stop(); settle()
        watcher.stop(); settle()
        assertEquals(0, observerCount())
    }
}
