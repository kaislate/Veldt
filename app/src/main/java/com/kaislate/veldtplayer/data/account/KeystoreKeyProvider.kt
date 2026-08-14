// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real key: a non-exportable AES-256 key in the Android Keystore, created on first use.
 *
 * **This class has no unit test and cannot have one** — `KeyStore.getInstance
 * ("AndroidKeyStore")` throws `KeyStoreException: AndroidKeyStore not found` under
 * Robolectric (verified 2026-08-14). It is deliberately as small as it can be so that
 * everything worth testing lives in [SecretBox] instead. Verify it on a device.
 *
 * No `setUserAuthenticationRequired`: the app must be able to decrypt in the background, for a
 * playback session started from a headset button, with the screen locked.
 */
@Singleton
class KeystoreKeyProvider @Inject constructor() : KeyProvider {

    override fun secretKey(): SecretKey? = try {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
    } catch (_: Exception) {
        // Intentionally broad. A missing provider, a corrupt keystore, and a key invalidated
        // by an OS update all arrive here as different exception types, and the contract is
        // the same for all of them: no key, so the account asks for its password again.
        null
    }

    private fun generate(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "veldt-account-secrets"
    }
}
