// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

/**
 * What happened when an account write was attempted.
 *
 * `add` used to return a bare `String` and `updateCredentials` a bare `Unit`, which collapsed
 * three genuinely different outcomes into one. The one that matters is [SecretUnavailable]: the
 * caller has *just successfully probed* these credentials against the real server, so reporting
 * "wrong password" there sends the user into a retry that fails identically, forever. The device's
 * secure storage being unavailable is not a credential problem and must not be shown as one.
 *
 * [InvalidUrl] exists because the repository normalises its own base URL rather than trusting the
 * UI to have done it (see `SubsonicUrls.normalizeBase`) — which means the repository, not the UI,
 * is now the place that can find a typed address unusable.
 */
sealed interface AccountWriteResult {

    /** The row and the sealed secret are both in place. */
    data class Saved(val sourceId: String) : AccountWriteResult

    /** The address could not be turned into a base URL, so nothing was written at all. */
    data object InvalidUrl : AccountWriteResult

    /**
     * The row exists, but the password could not be sealed or written — secure storage is
     * unavailable on this device.
     *
     * The row is deliberately kept: `Account.hasSecret` reads false, the screen offers "enter it
     * again", and the account is not silently lost. What the caller must NOT do is blame the
     * credential.
     */
    data class SecretUnavailable(val sourceId: String) : AccountWriteResult

    /**
     * There is no account with that id, so an edit had nothing to edit.
     *
     * Reachable only from `updateCredentials`, and only as a race with a delete on another
     * screen. It used to be an early `return` indistinguishable from success.
     */
    data object NoSuchAccount : AccountWriteResult
}
