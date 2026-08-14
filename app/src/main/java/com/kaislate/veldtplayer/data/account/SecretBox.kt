// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-GCM over a key from [KeyProvider]. The stored form is `iv || ciphertext-with-tag`.
 *
 * **Why this is hand-rolled.** Jetpack Security's `EncryptedSharedPreferences` was deprecated
 * by Google in 2024, so the dependency-free answer is roughly sixty lines of `javax.crypto`
 * plus tests — which is what this is.
 *
 * **Why every failure is null and none is an exception.** The realistic failure is a hardware
 * Keystore invalidating its key across an OS update, which this project's device fleet does
 * occasionally. The user-visible consequence must be "this account needs its password again",
 * never a crash and never a silently empty library.
 */
@Singleton
class SecretBox @Inject constructor(private val keys: KeyProvider) {

    /**
     * **The IV is the cipher's to choose, never ours.** [KeystoreKeyProvider] creates its key with
     * `setRandomizedEncryptionRequired(true)`, and a key carrying that flag *forbids* a
     * caller-supplied nonce: an `init(ENCRYPT_MODE, key, GCMParameterSpec(...))` is rejected on a
     * real device with `CALLER_NONCE_PROHIBITED`, which arrives here as a
     * [GeneralSecurityException] and turns every password into a silently discarded null. So
     * encryption passes no spec and reads [Cipher.getIV] back afterwards. That spelling is right
     * for both key kinds — a software key generates a random IV when none is supplied, and the
     * Keystore key insists on exactly that. Decryption is the opposite case: there the IV *must*
     * be supplied, and doing so is legal for both.
     */
    fun seal(plaintext: String): ByteArray? {
        val key = keys.secretKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    fun open(sealed: ByteArray): String? {
        val key = keys.secretKey() ?: return null
        // A blob shorter than iv+tag cannot be one of ours. Checked before slicing so a
        // truncated file produces null rather than an IndexOutOfBounds.
        if (sealed.size < IV_BYTES + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES),
            )
            String(
                cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES),
                Charsets.UTF_8,
            )
        } catch (_: GeneralSecurityException) {
            // Covers AEADBadTagException (wrong key or tampered bytes) and every other
            // javax.crypto failure. Do NOT narrow this: the whole contract is that no
            // corrupted input reaches a caller as an exception.
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * 12 bytes is GCM's standard nonce length, and what every provider this app runs on —
         * Conscrypt, the Keystore, the JVM's SunJCE — returns from [Cipher.getIV]. [seal] no
         * longer *chooses* the length, so this is the length at which stored blobs are *parsed*:
         * changing it invalidates every stored secret, and a provider that returned some other
         * length would produce blobs this code could not read back.
         */
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
