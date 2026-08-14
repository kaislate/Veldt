// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import androidx.media3.common.PlaybackException

/** What [PlaybackConnection]'s error listener should do about one `PlaybackException`. */
internal enum class ErrorAction {
    /**
     * Advance to the next item, exactly as before this policy existed — including the
     * `consecutiveErrors >= mediaItemCount` bound that stops an all-dead queue from spinning.
     */
    SKIP,

    /**
     * Stay on the current item at the current position and stop playing. Nothing retries on a
     * timer; the user's next tap on play re-prepares the same item, which is the point — by
     * then the radio is usually back.
     */
    PAUSE_IN_PLACE,
}

/**
 * The playback error policy (spec §7.3): whether an error is a property of the *item* or of the
 * *network*.
 *
 * Skipping is right for a dead item and wrong for a dead network. A local library only ever
 * produces the former, so skip-on-any-error was correct until N0 opened the door to remote
 * sources; with a stream behind it, one Wi-Fi blip skips the entire queue, because every
 * subsequent item hits the same dead network and the `consecutiveErrors` bound then stops
 * playback outright. One dropped packet and the queue is gone.
 *
 * Only two codes are safe to treat as "the network, not this item". They are the whole
 * `ERROR_CODE_IO_NETWORK_CONNECTION_*` family in the version on the classpath
 * (`androidx.media3:media3-common:1.8.0`, values read with `javap -p -constants` on the
 * `classes.jar` inside the resolved `.aar`, not copied from a plan), and they are the two that
 * mean *the request got no answer at all*: 2001 `..._FAILED` and 2002 `..._TIMEOUT`. Nothing
 * about the item is implicated, so advancing past it discards a track that is probably fine.
 *
 * ### Why `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004) is deliberately NOT in the pause set
 *
 * It is the tempting one, and it is the wrong one. Media3 raises it for *any* non-2xx, so a
 * single code covers both a 401 on an expired token (retry-worthy) and a 404 on a file that was
 * deleted last month (permanently dead). The response code that would tell them apart is on the
 * exception's cause, not on `errorCode`, so this function genuinely cannot distinguish them.
 * Forced to pick one behaviour for both:
 *
 *  - **The server answered.** That is positive evidence the network is up, which is the exact
 *    premise the pause branch rests on ("the next item will fail the same way") — and it fails.
 *    The next item may be perfectly reachable.
 *  - **The two errors are not equally costly.** Skipping something retry-worthy costs one track
 *    and playback continues. Pausing on something permanently gone stops playback dead, and
 *    because pause has no retry loop, the user's next tap on play re-prepares the same dead URL
 *    and pauses again — a dead end with no way out but rebuilding the queue.
 *  - **The 401 case is not reachable yet.** N0 adds the seam and nothing that uses it: no
 *    account, no credential store, no token refresh. Pausing on 2004 today would be waiting for
 *    a refresh that nothing in the process performs. When N1 adds tokens, the fix is a refresh
 *    keyed on the response code off the cause — not a blanket pause keyed on 2004. Leaving 2004
 *    in the skip set keeps that seam honest instead of pre-committing to a behaviour that
 *    nothing can yet deliver.
 *
 * For the same reason `ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE` (2003) skips — the server
 * answered, with the wrong thing — as do `ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED` (2007, a
 * configuration fault that a retry cannot fix) and `ERROR_CODE_IO_UNSPECIFIED` (2000, which also
 * covers plain local IO).
 *
 * Written as an explicit set rather than a range: an unrecognised code — everything a future
 * Media3 adds — must fall to [ErrorAction.SKIP], the behaviour that at worst loses one track,
 * never the one that can stall playback with no recovery.
 */
internal fun errorAction(errorCode: Int): ErrorAction = when (errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    -> ErrorAction.PAUSE_IN_PLACE

    else -> ErrorAction.SKIP
}

/**
 * The counter half of the same policy, extracted for one reason: it is the only way the rule
 * gets asserted at all. `PlaybackConnection.consecutiveErrors` is private to an object whose
 * constructor immediately opens a `MediaController` against a real service, so it is out of
 * reach of the JVM suite.
 *
 * A [ErrorAction.PAUSE_IN_PLACE] must leave the count alone. If pausing incremented it, a long
 * outage would walk the count up to `mediaItemCount` anyway and the bound would stop playback —
 * the very defect the pause branch was added to remove, wearing a different hat. Only a genuine
 * skip has consumed an item, so only a skip counts one.
 */
internal fun nextConsecutiveErrors(current: Int, action: ErrorAction): Int = when (action) {
    ErrorAction.SKIP -> current + 1
    ErrorAction.PAUSE_IN_PLACE -> current
}
