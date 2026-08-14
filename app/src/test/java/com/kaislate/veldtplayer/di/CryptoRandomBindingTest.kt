// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import com.kaislate.veldtplayer.data.net.SubsonicClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method
import java.util.Random

/**
 * The salt RNG is bound under a qualifier, and read back from the bytecode rather than believed.
 *
 * `@Provides fun provideRandom(): Random` claimed the **unqualified** `java.util.Random` key in
 * `SingletonComponent`. Nothing was wrong with it today — Hilt binds a `SecureRandom` and
 * `SecretBox` builds its own — but a music player wants a shuffle RNG, and the next `@Provides
 * Random` either breaks the build or, worse, is satisfied by *this* one. Someone then reseeds it
 * for a deterministic-shuffle feature and salt generation silently degrades to a predictable
 * `Random`, which makes the `t=` token pre-computable. Shape unchanged, one value moved between
 * two correct-looking sets, and no behavioural test would ever see it.
 *
 * A Hilt component test would be the direct check and is not available offline; reflection over
 * the annotations Dagger itself reads is the same claim from the same source of truth.
 */
class CryptoRandomBindingTest {

    private fun Method.isCryptoQualified(): Boolean =
        annotations.any { it.annotationClass == CryptoRandom::class }

    @Test fun `every Random binding in NetworkModule is qualified as the crypto one`() {
        val providers = NetworkModule::class.java.declaredMethods
            .filter { it.returnType == Random::class.java }
        // Guards the guard: zero providers would satisfy an "all are qualified" assertion while
        // meaning the module had been renamed out from under this test.
        assertEquals(
            "expected exactly one Random provider in NetworkModule",
            listOf("provideRandom"),
            providers.map { it.name }.sorted(),
        )
        // WHICH provider is unqualified, not how many.
        assertEquals(
            "these Random providers claim the unqualified java.util.Random key",
            emptyList<String>(),
            providers.filterNot { it.isCryptoQualified() }.map { it.name },
        )
    }

    @Test fun `SubsonicClient asks for the crypto Random, not any Random`() {
        val constructor = SubsonicClient::class.java.declaredConstructors.single()
        val index = constructor.parameterTypes.indexOfFirst { it == Random::class.java }
        assertEquals("SubsonicClient no longer takes a Random at all", true, index >= 0)

        val qualifiers = constructor.parameterAnnotations[index]
            .map { it.annotationClass.simpleName }
        assertEquals(
            "the injection site takes an unqualified Random, so a future shuffle RNG can satisfy it",
            true,
            CryptoRandom::class.simpleName in qualifiers,
        )
    }
}
