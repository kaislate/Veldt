// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.Key
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.KeyGenerator
import javax.crypto.NoSuchPaddingException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Plain JVM. AndroidKeyStore is unreachable under Robolectric (Global Constraint 5), which is
 * exactly why [SecretBox] takes a [KeyProvider] — everything with an interesting failure mode
 * is on this side of the seam.
 */
class SecretBoxTest {

    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun boxWith(key: SecretKey?) = SecretBox(object : KeyProvider {
        override fun secretKey(): SecretKey? = key
    })

    @Test fun `a sealed secret opens back to itself`() {
        val box = boxWith(freshKey())
        val sealed = box.seal("correct horse battery staple") ?: error("seal returned null")
        assertEquals("correct horse battery staple", box.open(sealed))
    }

    @Test fun `an empty secret round trips`() {
        val box = boxWith(freshKey())
        assertEquals("", box.open(box.seal("")!!))
    }

    @Test fun `non-ascii survives the round trip`() {
        val box = boxWith(freshKey())
        val secret = "pässwörd–ünïcode-éè"
        assertEquals(secret, box.open(box.seal(secret)!!))
    }

    @Test fun `the plaintext never appears in the sealed bytes`() {
        val box = boxWith(freshKey())
        val sealed = box.seal("hunter2")!!
        assertEquals(
            "the plaintext is recoverable from the ciphertext",
            false,
            String(sealed, Charsets.ISO_8859_1).contains("hunter2"),
        )
    }

    @Test fun `sealing the same secret twice produces different bytes`() {
        val box = boxWith(freshKey())
        val a = box.seal("hunter2")!!
        val b = box.seal("hunter2")!!
        assertNotEquals(
            "identical ciphertexts mean a reused IV, which breaks GCM catastrophically",
            String(a, Charsets.ISO_8859_1),
            String(b, Charsets.ISO_8859_1),
        )
        // ...and both still open, so the difference is the IV and not corruption.
        assertEquals("hunter2", box.open(a))
        assertEquals("hunter2", box.open(b))
    }

    @Test fun `a different key opens nothing — this is the key-invalidation path`() {
        // An OS update that invalidates the Keystore key presents exactly like this: the
        // ciphertext is intact and the key that made it is gone. It must degrade to null so
        // the account can say "sign in again", never crash and never silently succeed.
        val sealed = boxWith(freshKey()).seal("hunter2")!!
        assertNull(boxWith(freshKey()).open(sealed))
    }

    @Test fun `no key at all means seal and open both decline rather than throw`() {
        val box = boxWith(null)
        assertNull(box.seal("hunter2"))
        assertNull(box.open(ByteArray(64)))
    }

    @Test fun `every corrupted shape opens to null and none of them throws`() {
        val key = freshKey()
        val box = boxWith(key)
        val good = box.seal("hunter2")!!

        val tamperedTag = good.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val tamperedIv = good.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val truncated = good.copyOf(good.size - 1)
        val corruptions = listOf(
            "tampered tag" to tamperedTag,
            "tampered iv" to tamperedIv,
            "truncated" to truncated,
            "empty" to ByteArray(0),
            "shorter than an iv" to ByteArray(4),
            "iv only" to good.copyOf(12),
        )
        val survivors = corruptions.filter { (_, bytes) -> box.open(bytes) != null }
        assertEquals("these corrupted inputs did not open to null", emptyList<Pair<String, ByteArray>>(), survivors)

        // The good one still opens, so the assertion above is not passing because open() is
        // simply always null.
        assertEquals("hunter2", box.open(good))
    }

    @Test fun `the sealed blob is iv-then-ciphertext with a 12-byte iv`() {
        // Pins the WIRE FORMAT. A round trip cannot see this: a seal and an open that both
        // moved to a 16-byte iv would agree with each other and silently fail to read every
        // secret already on a user's device.
        val box = boxWith(freshKey())
        val sealed = box.seal("")!!
        assertEquals(12 + 16, sealed.size) // 12-byte iv + 16-byte GCM tag over empty plaintext
    }

    @Test fun `a blob sealed by one box opens in another sharing the key`() {
        // Round-trips through the same key rather than a hardcoded blob, because the key is
        // random; what is pinned is that seal's output is exactly what open consumes, across
        // instances — the real case is a process restart, not one object talking to itself.
        val key = freshKey()
        val sealed = boxWith(key).seal("hunter2")!!
        assertEquals("hunter2", boxWith(key).open(sealed))
        // The blob's LENGTH is the format claim worth pinning: 12-byte iv + 7 plaintext
        // bytes + 16-byte tag. An `assertArrayEquals(sealed, sealed.copyOf())` stood here
        // originally and was a TAUTOLOGY — an array always equals its own copy, so it could
        // not fail under any implementation.
        assertEquals(12 + "hunter2".toByteArray().size + 16, sealed.size)
    }

    /** Decrypt [sealed] treating the FIRST 12 bytes as the IV — the layout this project ships. */
    private fun openWithLeadingIv(key: SecretKey, sealed: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        return String(cipher.doFinal(sealed.copyOfRange(12, sealed.size)), Charsets.UTF_8)
    }

    /** Decrypt [sealed] treating the LAST 12 bytes as the IV — the layout that must NOT work. */
    private fun openWithTrailingIv(key: SecretKey, sealed: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, sealed.copyOfRange(sealed.size - 12, sealed.size)),
        )
        return String(cipher.doFinal(sealed.copyOfRange(0, sealed.size - 12)), Charsets.UTF_8)
    }

    @Test fun `the LAYOUT is iv-then-ciphertext, read by a Cipher that is not SecretBox`() {
        // The two tests above pin the SIZE and claim to guard a coordinated seal/open change.
        // They do not. The surviving mutant is `ciphertext || iv` in seal with the iv read from
        // the tail in open: the sizes are identical (28 and 35), the round trip agrees with
        // itself, both tamper cases still fail the tag and all three short inputs still trip the
        // `size < 28` guard — every one of the ten tests here passed it, while EVERY secret
        // already on a user's device became permanently unreadable.
        //
        // The only way to say "iv first" executably is to decrypt through something that is not
        // SecretBox, so seal and open cannot agree with each other about a wrong answer.
        val key = freshKey()
        for (secret in listOf("", "hunter2", "pässwörd–ünïcode-éè")) {
            val sealed = boxWith(key).seal(secret) ?: error("seal returned null")
            assertEquals(
                "the first 12 bytes are not the IV for secret \"$secret\"",
                secret,
                openWithLeadingIv(key, sealed),
            )
            // Stated in both directions: under the mutant this reading is the one that WORKS,
            // so asserting only the positive leaves half the layout unpinned.
            assertThrows(
                AEADBadTagException::class.java,
            ) { openWithTrailingIv(key, sealed) }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The Keystore's contract. See the class KDoc on KeystoreKeyProvider.
    //
    // Every test above this line injects a key from KeyGenerator.getInstance("AES"), and a
    // software key PERMITS a caller-supplied nonce. The production key does not: it is built
    // with setRandomizedEncryptionRequired(true), and the Keystore rejects
    // init(ENCRYPT_MODE, key, GCMParameterSpec(...)) outright —
    //
    //     keystore2: NONCE is present, although CALLER_NONCE is not present
    //     keystore2: Error::Km(r#CALLER_NONCE_PROHIBITED)
    //
    // — which seal() catches as a GeneralSecurityException and turns into null, silently
    // discarding every password. That shipped, was found by a device pass on 2026-08-14, and
    // was invisible to all eleven tests above because the fixture and the real key disagreed
    // on exactly the axis that failed. A fixture is a claim about reachable states.
    //
    // AndroidKeyStore itself is unreachable here (Global Constraint 5 — KeyStore.getInstance
    // ("AndroidKeyStore") throws under Robolectric, and this file is plain JUnit anyway), so
    // what follows installs a JCE provider that enforces the same rule over a software key.
    // ---------------------------------------------------------------------------------------

    /** Runs [body] with [ProhibitingProvider] installed first in the JCE provider list. */
    private fun <T> withCallerNonceProhibited(body: () -> T): T {
        Security.insertProviderAt(ProhibitingProvider(jvmAesGcmProvider()), 1)
        return try {
            body()
        } finally {
            Security.removeProvider(PROHIBITING_PROVIDER)
        }
    }

    @Test fun `the prohibiting fixture really does forbid a caller nonce`() {
        // Guards the guard. If this fixture quietly permitted a caller-supplied IV, the test
        // below would pass under the very implementation it exists to reject, and would be
        // worth nothing.
        val key = ProhibitingKey(freshKey())
        withCallerNonceProhibited {
            assertThrows(GeneralSecurityException::class.java) {
                Cipher.getInstance("AES/GCM/NoPadding")
                    .init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, ByteArray(12)))
            }
            // ...while the spelling seal() must use is accepted, so the throw above is about
            // the nonce and not about the key being unusable altogether.
            Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, key)
        }
    }

    @Test fun `seal takes its IV from the cipher, because the real key forbids supplying one`() {
        // THE REGRESSION GUARD. Reverting seal() to
        //     val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        //     cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        // makes every assertion below fail on `seal returned null` — which is precisely what
        // the device did, and what no other test in this file can observe.
        val software = freshKey()
        val sealed = withCallerNonceProhibited {
            val box = boxWith(ProhibitingKey(software))
            val sealed = box.seal("hunter2")
                ?: error("seal returned null: it supplied its own IV, which the Keystore forbids")
            // The round trip still works through the same prohibiting key: decryption DOES
            // supply the IV, and that direction is legal for both key kinds.
            assertEquals("hunter2", box.open(sealed))
            sealed
        }
        // Same wire format as everywhere else — iv first, 12 bytes of it — read back by a
        // plain Cipher holding the underlying software key, outside the prohibiting provider.
        assertEquals(12 + "hunter2".toByteArray().size + 16, sealed.size)
        assertEquals("hunter2", openWithLeadingIv(software, sealed))
    }

    @Test fun `the IV seal now gets from the cipher is still random per call`() {
        // Delegating the nonce to the provider must not cost the property the old code got
        // from SecureRandom: a repeated (key, IV) pair under GCM is catastrophic.
        val software = freshKey()
        val ivs = withCallerNonceProhibited {
            val box = boxWith(ProhibitingKey(software))
            List(8) {
                val sealed = box.seal("hunter2")
                    ?: error("seal returned null: it supplied its own IV, which the Keystore forbids")
                sealed.copyOfRange(0, 12).toList()
            }
        }
        assertEquals("seal reused an IV", 8, ivs.toSet().size)
    }

    @Test fun `a provider-chosen GCM nonce is 12 bytes, which is what the wire format assumes`() {
        // seal() no longer chooses the IV length; the provider does, and open() parses at a
        // hardcoded 12. Pin the assumption rather than trusting it: a provider handing back a
        // 16-byte nonce would write blobs this app could never read.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, freshKey())
        assertEquals("a provider-chosen GCM nonce was not 12 bytes", 12, cipher.iv.size)
    }
}

private const val PROHIBITING_PROVIDER = "VeldtCallerNonceProhibited"

/** The JCE provider a plain software AES-GCM key resolves to on this JVM. */
private fun jvmAesGcmProvider(): Provider =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
    }.provider

/**
 * A key that behaves like an `AndroidKeyStoreSecretKey`: non-exportable, so no software provider
 * will touch it, and usable only through [ProhibitingProvider].
 *
 * The null format is what makes the fixture bite. `SunJCE` refuses a key whose format is not
 * `RAW`, so when [ProhibitingProvider] rejects a caller-supplied nonce there is nothing left to
 * fall back to and `Cipher.init` fails — exactly as it does on the device.
 */
private class ProhibitingKey(val software: SecretKey) : SecretKey {
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/**
 * Models `setRandomizedEncryptionRequired(true)`: AES-GCM that works normally except that
 * supplying parameters to an ENCRYPT_MODE init is refused, as the Android Keystore refuses it
 * with `CALLER_NONCE_PROHIBITED`. Serves [ProhibitingKey] and nothing else.
 */
@Suppress("DEPRECATION") // the (String, String, String) ctor is not in the android.jar stub
private class ProhibitingProvider(private val delegate: Provider) : Provider(
    PROHIBITING_PROVIDER,
    1.0,
    "test double for an Android Keystore key that forbids a caller-supplied nonce",
) {
    init {
        putService(object : Service(
            this,
            "Cipher",
            "AES/GCM/NoPadding",
            ProhibitingCipherSpi::class.java.name,
            null,
            null,
        ) {
            override fun newInstance(constructorParameter: Any?): Any =
                ProhibitingCipherSpi(Cipher.getInstance("AES/GCM/NoPadding", delegate))

            /** Never volunteer for an ordinary key: the other tests in this file must be unaffected. */
            override fun supportsParameter(parameter: Any?): Boolean = parameter is ProhibitingKey
        })
    }
}

private class ProhibitingCipherSpi(private val real: Cipher) : CipherSpi() {

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        real.init(opmode, unwrap(key), random ?: SecureRandom())
    }

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?,
    ) {
        refuseCallerNonce(opmode, params)
        real.init(opmode, unwrap(key), params, random ?: SecureRandom())
    }

    override fun engineInit(
        opmode: Int,
        key: Key?,
        params: AlgorithmParameters?,
        random: SecureRandom?,
    ) {
        refuseCallerNonce(opmode, params)
        real.init(opmode, unwrap(key), params, random ?: SecureRandom())
    }

    private fun refuseCallerNonce(opmode: Int, params: Any?) {
        if (opmode == Cipher.ENCRYPT_MODE && params != null) {
            throw InvalidAlgorithmParameterException(
                "CALLER_NONCE_PROHIBITED: NONCE is present, although CALLER_NONCE is not present",
            )
        }
    }

    private fun unwrap(key: Key?): Key = (key as? ProhibitingKey)?.software
        ?: throw InvalidKeyException("$PROHIBITING_PROVIDER only serves ProhibitingKey")

    override fun engineSetMode(mode: String?) {
        if (!"GCM".equals(mode, ignoreCase = true)) throw NoSuchAlgorithmException(mode)
    }

    override fun engineSetPadding(padding: String?) {
        if (!"NoPadding".equals(padding, ignoreCase = true)) throw NoSuchPaddingException(padding)
    }

    override fun engineGetBlockSize(): Int = real.blockSize
    override fun engineGetOutputSize(inputLen: Int): Int = real.getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = real.iv
    override fun engineGetParameters(): AlgorithmParameters? = real.parameters

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? =
        real.update(input, inputOffset, inputLen)

    override fun engineUpdate(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = real.update(input, inputOffset, inputLen, output, outputOffset)

    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {
        real.updateAAD(src, offset, len)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        real.doFinal(input, inputOffset, inputLen)

    override fun engineDoFinal(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = real.doFinal(input, inputOffset, inputLen, output, outputOffset)
}
