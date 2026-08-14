// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import com.kaislate.veldtplayer.playback.RemoteUriResolver
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Wiring for the playback-side seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {

    /**
     * Declares the `Set<RemoteUriResolver>` multibinding **without contributing to it**.
     *
     * This is the same shape `LibraryModule` uses for `LibrarySource` — future remote sources join
     * it with `@Binds @IntoSet` — but with one difference that has to be stated rather than
     * inferred: that set has an element today and this one has none. Dagger creates a multibinding
     * only where something contributes, so without this declaration `Set<RemoteUriResolver>` would
     * simply be a missing binding and `PlaybackUriResolver` would not be constructible. `@Multibinds`
     * is what makes "empty" a legal answer instead of an absent one.
     *
     * That empty set is this slice's real configuration and the entire app runs through it: every
     * uri passes through unchanged, exactly as it did before the seam existed. The first element
     * arrives with `SubsonicSource` in N2.
     */
    @Multibinds
    abstract fun remoteUriResolvers(): Set<RemoteUriResolver>
}
