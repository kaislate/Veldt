// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single-flight: a burst that outlives [ScanTrigger]'s debounce must not start a second scan over
 * the same rows. [LibraryScanWorker.enqueue] gets that from `enqueueUniqueWork(..., KEEP, ...)`,
 * and this pins it against the REAL WorkManager rather than a fake.
 *
 * **Why the real scheduler.** The property under test *is* WorkManager's `ExistingWorkPolicy`
 * semantics. A hand-rolled fake would have to implement KEEP-vs-REPLACE itself in order to
 * distinguish them, so the test would be asserting its own model and would pass under the
 * mutation. `work-testing` + Robolectric costs one test dependency and buys an assertion that
 * actually fails when `KEEP` becomes `REPLACE` (negative control NC-A).
 *
 * **Why the worker never finishes.** `KEEP` drops the incoming request only while the existing
 * one is unfinished. If the first scan ran to completion inline, the second enqueue would be
 * correct to create new work and the test would be vacuous — it would pass under `REPLACE` too.
 * [StuckWorker] returns a future that is never resolved, so the first request stays live and the
 * two policies are forced apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanSingleFlightTest {

    /** A worker that starts and never finishes; see the class KDoc. */
    private class StuckWorker(ctx: Context, params: WorkerParameters) :
        ListenableWorker(ctx, params) {
        override fun startWork(): ListenableFuture<Result> = NeverFuture()
    }

    private class NeverFuture : ListenableFuture<ListenableWorker.Result> {
        override fun addListener(listener: Runnable, executor: Executor) = Unit
        override fun cancel(mayInterruptIfRunning: Boolean) = false
        override fun isCancelled() = false
        override fun isDone() = false
        override fun get(): ListenableWorker.Result = throw UnsupportedOperationException()
        override fun get(timeout: Long, unit: TimeUnit): ListenableWorker.Result =
            throw UnsupportedOperationException()
    }

    /**
     * Substitutes [StuckWorker] for whatever class was requested, and counts how many worker
     * instances WorkManager decided to build — the "two rapid triggers enqueue ONE worker" claim
     * in its most literal form. The Hilt worker factory is unavailable in a plain Robolectric
     * test, so a substitute would be needed regardless.
     */
    private class CountingFactory : WorkerFactory() {
        val created = AtomicInteger(0)
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            created.incrementAndGet()
            return StuckWorker(appContext, workerParameters)
        }
    }

    private lateinit var context: Context
    private lateinit var factory: CountingFactory
    private lateinit var wm: WorkManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        factory = CountingFactory()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setWorkerFactory(factory)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
        wm = WorkManager.getInstance(context)
    }

    private fun scans(): List<WorkInfo> =
        wm.getWorkInfosForUniqueWork(LibraryScanWorker.UNIQUE_NAME).get()

    @Test
    fun `two rapid triggers enqueue one worker`() {
        LibraryScanWorker.enqueue(context)
        LibraryScanWorker.enqueue(context)

        val infos = scans()
        assertEquals("a second trigger must not add a second scan", 1, infos.size)
        assertTrue(
            "the in-flight scan must survive the second trigger, not be cancelled by it",
            infos.none { it.state == WorkInfo.State.CANCELLED },
        )
        assertEquals("exactly one worker instance", 1, factory.created.get())
    }

    /** A burst well past the debounce window is still one scan. */
    @Test
    fun `a long burst of triggers is still one scan`() {
        repeat(25) { LibraryScanWorker.enqueue(context) }

        val infos = scans()
        assertEquals(1, infos.size)
        assertTrue(infos.none { it.state == WorkInfo.State.CANCELLED })
        assertEquals(1, factory.created.get())
    }

    /**
     * The complement, and the reason the two tests above are not satisfied by a trigger that
     * simply does nothing: once the in-flight scan is gone, the next trigger MUST start a fresh
     * one. Without this, "swallow every enqueue after the first" would pass the whole file — and
     * that mutation would mean the library stops updating for the life of the process.
     */
    @Test
    fun `a trigger after the previous scan is gone starts a new scan`() {
        LibraryScanWorker.enqueue(context)
        assertEquals(1, factory.created.get())

        wm.cancelUniqueWork(LibraryScanWorker.UNIQUE_NAME).result.get()

        LibraryScanWorker.enqueue(context)
        assertEquals("a finished scan must not block the next one", 2, factory.created.get())
        assertTrue(
            "a live scan must exist again",
            scans().any { !it.state.isFinished },
        )
    }
}
