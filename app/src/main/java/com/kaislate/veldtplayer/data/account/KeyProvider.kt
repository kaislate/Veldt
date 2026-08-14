// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import javax.crypto.SecretKey

/**
 * Supplies the AES key [SecretBox] uses.
 *
 * This interface exists for one reason and it is worth stating plainly: under Robolectric,
 * `KeyStore.getInstance("AndroidKeyStore")` throws `KeyStoreException: AndroidKeyStore not
 * found`. Without a seam here, every test of the encryption — round trip, tamper detection,
 * IV uniqueness, the key-invalidation path — would be impossible off-device, and the one
 * thing standing between a user's password and anything that reads app storage would ship
 * unverified.
 *
 * Returning null rather than throwing is deliberate: a Keystore that has lost its key is a
 * recoverable "sign in again" state, not an error condition.
 */
interface KeyProvider {
    fun secretKey(): SecretKey?
}
