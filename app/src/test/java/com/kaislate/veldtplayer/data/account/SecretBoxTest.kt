// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
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
}
