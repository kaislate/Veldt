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
 * **What a device pass has to do**, since no unit test can: on a device with the alias absent,
 * call [secretKey] from two threads released together by a `CountDownLatch` and assert both
 * return the *same* `SecretKey` (`a == b`, or seal with one and open with the other); then seal
 * a secret, restart the process, and assert it still opens — which is what proves the second
 * caller did not overwrite the first caller's key.
 *
 * No `setUserAuthenticationRequired`: the app must be able to decrypt in the background, for a
 * playback session started from a headset button, with the screen locked.
 *
 * ## `setRandomizedEncryptionRequired(true)` is deliberate, and it constrains [SecretBox]
 *
 * That flag makes the Keystore refuse any encryption where the *caller* picks the nonce, so a
 * caller cannot reuse one. GCM with a repeated (key, IV) pair is catastrophic — it leaks the XOR
 * of the plaintexts and the authentication subkey — so the platform will not let the app choose.
 * **Do not turn it off to make an error go away.**
 *
 * The other half of that contract lives in a different file and cannot be seen from there:
 * **whoever encrypts with this key must NOT pass a `GCMParameterSpec` (or any IV) to
 * `Cipher.init(ENCRYPT_MODE, ...)`.** They must call `init(ENCRYPT_MODE, key)` and read the
 * chosen nonce back from `Cipher.getIV()`. Supplying one is rejected on-device with
 *
 * ```
 * keystore2: NONCE is present, although CALLER_NONCE is not present
 * keystore2: Error::Km(r#CALLER_NONCE_PROHIBITED)
 * ```
 *
 * which surfaces as a `GeneralSecurityException` and, because [SecretBox] degrades every crypto
 * failure to null, silently discards the secret. That shipped once (device pass, 2026-08-14) and
 * no unit test could see it: the test [KeyProvider] hands out a software key, and a software key
 * *permits* a caller nonce. Decryption is the reverse — the IV must be supplied there, and doing
 * so is legal for both key kinds.
 */
@Singleton
class KeystoreKeyProvider @Inject constructor() : KeyProvider {

    /**
     * The key, once loaded. Written only under the lock in [secretKey].
     *
     * `@Volatile` so the fast path may read it without taking the monitor: a hit is the
     * overwhelmingly common case, and this is called per row per flow emission.
     */
    @Volatile
    private var cached: SecretKey? = null

    /**
     * The key, creating it on first use.
     *
     * **`@Synchronized` is correctness, not tidiness.** `generate()` *replaces* whatever sits
     * under [ALIAS], and the load-then-generate below is a check-then-act. Two concurrent first
     * touches — one from `SecretBox.seal` inside `AccountRepository.add`, one from
     * `SecretBox.open` inside `password()`, which runs on `Dispatchers.IO` — would both miss and
     * both generate, the second silently overwriting the first, and the blob just sealed could
     * never be opened again. Permanent, silent loss of the credential.
     *
     * Only a non-null key is cached: a transient failure (keystore busy, provider not yet up)
     * must not be remembered as "this device has no key forever".
     */
    @Synchronized
    override fun secretKey(): SecretKey? {
        cached?.let { return it }
        return try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val key = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generate()
            key.also { cached = it }
        } catch (_: Exception) {
            // Intentionally broad. A missing provider, a corrupt keystore, and a key invalidated
            // by an OS update all arrive here as different exception types, and the contract is
            // the same for all of them: no key, so the account asks for its password again.
            null
        }
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
