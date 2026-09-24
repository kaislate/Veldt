// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.net.SubsonicAuth
import com.kaislate.veldtplayer.data.net.SubsonicCredentials
import com.kaislate.veldtplayer.data.net.SubsonicStreamUrls
import com.kaislate.veldtplayer.di.CryptoRandom
import kotlinx.coroutines.runBlocking
import java.util.Random
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every server account as a playback resolver (N2 Task 4): `veldt://track/<account>/<id>` becomes
 * that account's authed `stream` url at load time.
 *
 * A [RemoteResolverLookup] rather than `@IntoSet` elements because accounts are added and removed
 * at runtime, and a Hilt set is fixed at component creation.
 *
 * **`runBlocking` is deliberate.** `ResolvingDataSource` calls the resolver on ExoPlayer's loader
 * thread, which exists to block on IO; [SubsonicSources.credentials] suspends only because its
 * first call per account reads Room and decrypts through the Keystore, and caches the result.
 *
 * A fresh salt is minted per resolve — per `open`, since `ResolvingDataSource` resolves every open
 * — from the `@CryptoRandom` CSPRNG. The resulting url carries `t=`/`s=` and goes nowhere but the
 * resolved `DataSpec`; `VeldtDataSpecResolver.resolveReportedUri` redacts it before it can reach
 * `LoadEventInfo`.
 */
@Singleton
class SubsonicStreamResolvers internal constructor(
    private val known: (String) -> Boolean,
    private val credentials: suspend (String) -> SubsonicCredentials?,
    private val quality: StreamQuality,
    private val random: Random,
) : RemoteResolverLookup {

    @Inject constructor(
        sources: SubsonicSources,
        quality: StreamQuality,
        @CryptoRandom random: Random,
    ) : this(
        known = sources::contains,
        credentials = { sources.credentials(it) },
        quality = quality,
        random = random,
    )

    override fun resolverFor(sourceId: String): RemoteUriResolver? {
        if (!known(sourceId)) return null
        return object : RemoteUriResolver {
            override val sourceId: String = sourceId

            override fun resolve(ref: TrackRef): String? =
                runBlocking { credentials(ref.sourceId) }?.let { creds ->
                    SubsonicStreamUrls.stream(
                        creds,
                        ref.externalId,
                        SubsonicAuth.newSalt(random),
                        quality.currentMaxBitRate(),
                    )?.toString()
                }
        }
    }
}
