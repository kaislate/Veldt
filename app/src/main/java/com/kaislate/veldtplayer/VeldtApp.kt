package com.kaislate.veldtplayer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kaislate.veldtplayer.data.art.AlbumArtFetcher
import com.kaislate.veldtplayer.data.art.AlbumArtKeyer
import dagger.hilt.android.HiltAndroidApp
import ealvatag.tag.TagOptionSingleton
import javax.inject.Inject

@HiltAndroidApp
class VeldtApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

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
            add(AlbumArtFetcher.Factory(this@VeldtApp))
        }
        .crossfade(false)
        .build()
}
