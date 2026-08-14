// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import javax.inject.Qualifier

/**
 * Marks the one `java.util.Random` that must be a CSPRNG.
 *
 * The provider binds a `SecureRandom` and its only consumer is Subsonic salt generation, where
 * predictability is the whole attack: the `t=` token is `md5(password + salt)`, so a salt an
 * attacker can predict is a token an attacker can pre-compute.
 *
 * **Why the binding may not stay unqualified.** `@Provides fun provideRandom(): Random` claims
 * the *unqualified* `java.util.Random` key in `SingletonComponent`, and a music player wants a
 * shuffle RNG. The next `@Provides Random` either breaks the build or — the outcome that
 * matters — someone reuses this one for shuffle and later reseeds it for a
 * deterministic-shuffle feature, at which point salt generation silently degrades to a
 * predictable `Random`. Shape unchanged, one value moved between two correct-looking sets, and
 * no behavioural test would see it (Global Constraint 4). A qualifier makes the two keys
 * different types, so the mix-up cannot be spelled.
 */
/*
 * RUNTIME retention, unlike [LocalLibrary]'s BINARY: JSR-330 specifies qualifiers as
 * `@Retention(RUNTIME)`, and it is what lets `CryptoRandomBindingTest` read the binding back off
 * the provider instead of asserting the rule as prose.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class CryptoRandom
