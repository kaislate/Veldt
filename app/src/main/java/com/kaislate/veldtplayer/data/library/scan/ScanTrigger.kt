// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.scan

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map

/**
 * The scan-triggering *policy*, deliberately separated from the thing that observes MediaStore.
 *
 * [MediaStoreWatcher] owns a `ContentObserver` and a `WorkManager` call, neither of which can be
 * driven from a JVM unit test. Everything that is a *decision* lives here instead, as a pure
 * function over a [Flow], so the interesting behaviour is pinned by `ScanTriggerTest` in virtual
 * time rather than argued for in a comment.
 */
object ScanTrigger {

    /**
     * The trailing window. Two seconds, not two hundred milliseconds: MediaStore's notifications
     * for a single copied album arrive over the whole time the media scanner is walking the new
     * files, with sub-second gaps, and a window shorter than those gaps splits one logical change
     * into several scans.
     */
    const val DEFAULT_WINDOW_MS: Long = 2_000L

    /**
     * Collapse a burst of change notifications into one scan request.
     *
     * **Trailing, not leading.** The emission lands one [windowMs] after the LAST event of a
     * burst, not on the first. Both edges produce "exactly one scan" for a burst — which is why
     * `ScanTriggerTest` asserts the *time* of the emission and not only the count — but only the
     * trailing edge scans a settled library. MediaStore starts notifying while the scanner is
     * still walking the new files, so a leading-edge scan enumerates a half-indexed album and
     * then, because [ScanDiffer] keys on `(externalId, dateModifiedSec)` and those rows have not moved
     * since, never revisits it.
     *
     * Two consequences worth knowing, both asserted in the test rather than merely stated:
     *
     * - **A source that ENDS flushes its pending event immediately** rather than waiting out the
     *   window. That is [debounce]'s contract, and it is why the single-event case says nothing
     *   about which edge fired.
     * - **Notifications arriving faster than [windowMs], forever, defer the scan forever.** This
     *   is the deliberate trade — a scan taken mid-copy reads a partial album — and real bursts
     *   do end. It is a live concern only for a device writing audio continuously, which nothing
     *   in this app does. Paired with [LibraryScanWorker]'s `KEEP` single-flight, the failure mode
     *   under sustained churn is "one scan, late", never "a scan restarted forever".
     *
     * The event payload is dropped: what changed does not matter, only that something did.
     * [LibraryScanWorker] re-enumerates and re-diffs regardless.
     */
    @OptIn(FlowPreview::class)
    fun coalesce(events: Flow<Long>, windowMs: Long = DEFAULT_WINDOW_MS): Flow<Unit> =
        events.debounce(windowMs).map { }
}
