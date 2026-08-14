// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import com.kaislate.veldtplayer.data.account.KeyProvider
import com.kaislate.veldtplayer.data.account.KeystoreKeyProvider
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.LocalSource
import com.kaislate.veldtplayer.data.library.scan.MediaStoreWatcher
import com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader
import com.kaislate.veldtplayer.data.library.tag.TagReader
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
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

    /**
     * The local source joins a **set**, not a single binding.
     *
     * There is deliberately no way left to inject a bare `LibrarySource`: "the" source stopped
     * being a meaningful thing when a song, a playlist entry and a media item each started naming
     * the source they belong to. Consumers take
     * [com.kaislate.veldtplayer.data.library.SourceRegistry] and route on that id; the two classes
     * that are MediaStore-specific by design — `LibraryScanWorker` and `PlaylistImporter` — inject
     * the concrete [LocalSource] instead, so the *type* states the restriction and no string has to
     * (Global Constraint 1).
     *
     * `@Singleton` is on [LocalSource]'s own declaration, not here: an `@IntoSet` binding scoped at
     * the module would scope the set element, which is not the same statement.
     */
    @Binds
    @IntoSet
    abstract fun bindLocalSource(impl: LocalSource): LibrarySource

    /**
     * The same instance again, under [LocalLibrary], for the two consumers that are MediaStore-
     * specific by design. `LocalSource` itself carries `@Singleton`, so this binding and the set
     * element above resolve to one object — the scope lives on the class precisely so these two
     * bindings cannot drift into two instances.
     */
    @Binds
    @LocalLibrary
    abstract fun bindLocalLibrary(impl: LocalSource): LibrarySource

    @Binds
    @Singleton
    abstract fun bindTagReader(impl: EAlvaTagReader): TagReader

    /**
     * The real key provider. Bound here rather than annotated `@Inject` at the use site so a
     * test — or a future debug build — can substitute one without touching [SecretBox].
     */
    @Binds
    @Singleton
    abstract fun bindKeyProvider(impl: KeystoreKeyProvider): KeyProvider

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
