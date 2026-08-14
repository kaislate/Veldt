// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * One OkHttp client for the whole app.
 *
 * A single client means a single connection pool and a single dispatcher, which is what
 * OkHttp's own guidance asks for. Coil already carries OkHttp transitively and builds its own
 * client today; unifying them is an N2 concern, noted here so it is a decision rather than an
 * oversight.
 *
 * Timeouts are explicit because OkHttp's defaults are 10s connect/read/write and NO overall
 * call timeout, so a server that dribbles bytes can hang a request indefinitely. A "test
 * connection" button that never returns is worse than one that fails.
 *
 * **No logging interceptor, in any build type.** Auth rides in the query string, so a logger
 * would write `t=` and `s=` into logcat. If one is ever added it must redact through
 * [com.kaislate.veldtplayer.data.net.SubsonicAuth.redact].
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * `SecureRandom` for salts. Provided rather than constructed inside
     * [com.kaislate.veldtplayer.data.net.SubsonicClient] so tests can inject a seeded
     * `Random` and assert an exact token.
     *
     * Qualified: see [CryptoRandom] for why an unqualified `java.util.Random` binding is a trap
     * in an app that will also want a shuffle RNG.
     */
    @Provides
    @Singleton
    @CryptoRandom
    fun provideRandom(): Random = SecureRandom()
}
