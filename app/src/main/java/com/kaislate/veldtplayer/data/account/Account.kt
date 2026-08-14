// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

/**
 * What the UI sees. Carries no secret and no way to reach one.
 *
 * [hasSecret] is the answer to "can this account authenticate right now" and is false both
 * when nothing was ever stored and when the Keystore key has been invalidated — the two cases
 * are indistinguishable to a user and call for the same action, re-entering the password.
 */
data class Account(
    val sourceId: String,
    val displayName: String,
    val baseUrl: String,
    val username: String,
    val hasSecret: Boolean,
)
