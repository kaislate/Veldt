// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import android.net.ConnectivityManager
import com.kaislate.veldtplayer.playback.NetworkMeter
import com.kaislate.veldtplayer.playback.RemoteResolverLookup
import com.kaislate.veldtplayer.playback.RemoteUriResolver
import com.kaislate.veldtplayer.playback.SubsonicStreamResolvers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Wiring for the playback-side seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    /**
     * Declares the `Set<RemoteUriResolver>` multibinding **without contributing to it**.
     *
     * This is the same shape `LibraryModule` uses for `LibrarySource` — build-time sources join it
     * with `@Binds @IntoSet` — but with one difference that has to be stated rather than inferred:
     * that set has an element today and this one has none. Dagger creates a multibinding only
     * where something contributes, so without this declaration `Set<RemoteUriResolver>` would
     * simply be a missing binding and `PlaybackUriResolver` would not be constructible. `@Multibinds`
     * is what makes "empty" a legal answer instead of an absent one.
     *
     * Server accounts do NOT join this set: they exist only at runtime, so they are answered by
     * [remoteResolverLookup] instead.
     */
    @Multibinds
    abstract fun remoteUriResolvers(): Set<RemoteUriResolver>

    /** Server accounts, resolved at load time (N2 Task 4). */
    @Binds
    abstract fun remoteResolverLookup(impl: SubsonicStreamResolvers): RemoteResolverLookup

    companion object {
        /**
         * Asked once per stream open. `isActiveNetworkMetered` needs `ACCESS_NETWORK_STATE`, which
         * the manifest declares; it answers `true` when there is no active network, which only
         * matters when nothing can load anyway.
         */
        @Provides
        fun provideNetworkMeter(@ApplicationContext context: Context): NetworkMeter {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return NetworkMeter { cm.isActiveNetworkMetered }
        }
    }
}
