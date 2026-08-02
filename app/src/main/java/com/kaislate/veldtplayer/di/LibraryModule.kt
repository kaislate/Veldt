// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.LocalSource
import com.kaislate.veldtplayer.data.library.scan.MediaStoreWatcher
import com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader
import com.kaislate.veldtplayer.data.library.tag.TagReader
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** Binds the P1 library-layer implementations to their framework-free interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LibraryModule {

    @Binds
    @Singleton
    abstract fun bindLibrarySource(impl: LocalSource): LibrarySource

    @Binds
    @Singleton
    abstract fun bindTagReader(impl: EAlvaTagReader): TagReader

    companion object {
        /**
         * `@Provides` rather than an `@Inject` constructor so the watcher's scope is a wiring
         * decision made here, not a field the class hardcodes: a test constructs it with a
         * `TestScope` and gets deterministic registration (see `MediaStoreWatcherTest`).
         *
         * `SupervisorJob` so a failure in the collector cannot take down anything else that
         * might later share this scope; `Dispatchers.Default` because the only work on it is the
         * debounce timer and a WorkManager enqueue. Process-scoped on purpose — the watcher
         * outlives the Activity so a library change during background playback is still noticed.
         */
        @Provides
        @Singleton
        fun provideMediaStoreWatcher(@ApplicationContext context: Context): MediaStoreWatcher =
            MediaStoreWatcher(
                context = context,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
    }
}
