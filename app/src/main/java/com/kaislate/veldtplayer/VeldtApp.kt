// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kaislate.veldtplayer.data.art.AlbumArtFetcher
import com.kaislate.veldtplayer.data.art.AlbumArtKeyer
import com.kaislate.veldtplayer.data.art.RemoteArtLoader
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import ealvatag.tag.TagOptionSingleton
import javax.inject.Inject

@HiltAndroidApp
class VeldtApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    /**
     * So browse screens and now-playing show a streamed track's server art (spec §5.6).
     *
     * [Lazy], not a direct [RemoteArtLoader] — this field is injected as part of `VeldtApp`'s
     * OWN construction, which Hilt's generated `Hilt_VeldtApp` runs on EVERY app start,
     * including every Robolectric unit test in this project (`VeldtApp` is the manifest
     * `android:name`, and nothing overrides it for tests). `RemoteArtLoader`'s dependency chain
     * ends at `SubsonicSources`, whose `@Inject constructor` starts a LIVE background
     * `accountDao.observeAll()` collector against the real, `@Singleton`, file-backed Room
     * database — measured: eagerly constructing that once per Robolectric test, for the
     * lifetime of ~900+ test methods across the whole suite, is what was actually leaking
     * uncaught exceptions onto unrelated tests once N2 Task 6 added this field, not anything
     * about this task's OWN new test files. `Lazy` defers construction to [newImageLoader],
     * which real Coil usage calls but a plain Robolectric unit test never does.
     */
    @Inject lateinit var remoteArt: Lazy<RemoteArtLoader>

    override fun onCreate() {
        super.onCreate()
        // MANDATORY: without this, eAlvaTag walks AWT/Swing/NIO code paths that do
        // not exist on Android and crashes on the first parse. Must run before any
        // AudioFileIO.read(...) call. Verify the class path resolves against 0.4.6:
        // ealvatag.tag.TagOptionSingleton.
        TagOptionSingleton.getInstance().isAndroid = true
    }

    // Custom WorkManager config so @HiltWorker workers can be constructed.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Coil finds this automatically (ImageLoaderFactory) — no manual wiring needed.
     * Crossfade is OFF on purpose: Veldt does its own transitions through the motion
     * system, and Coil's would fight the shared-element morph.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(AlbumArtKeyer())
            add(AlbumArtFetcher.Factory(this@VeldtApp, remoteArt.get()))
        }
        .crossfade(false)
        .build()
}
