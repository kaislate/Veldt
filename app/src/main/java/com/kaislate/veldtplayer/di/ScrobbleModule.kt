// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import com.kaislate.veldtplayer.data.scrobble.ScrobbleFlushScheduler
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import com.kaislate.veldtplayer.data.scrobble.WorkManagerScrobbleFlushScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Wires N3 scrobbling's file-backed queue and its flush scheduler. [com.kaislate.veldtplayer
 *  .data.scrobble.ScrobbleFlusher] needs nothing declared here — Dagger reaches it through its
 *  own `@Inject` constructor. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScrobbleModule {

    @Binds
    @Singleton
    abstract fun bindScrobbleFlushScheduler(impl: WorkManagerScrobbleFlushScheduler): ScrobbleFlushScheduler

    companion object {
        /** `filesDir/scrobbles` — deliberately NOT `cacheDir` (unlike [com.kaislate.veldtplayer
         *  .data.lyrics.LrclibCache]): the OS may evict `cacheDir` under storage pressure, and a
         *  queued "played" scrobble is a real listen the user had, not a re-fetchable cache
         *  entry — design spec §4. */
        @Provides
        @Singleton
        fun provideScrobbleQueue(@ApplicationContext context: Context): ScrobbleQueue =
            ScrobbleQueue(File(context.filesDir, "scrobbles"))
    }
}
