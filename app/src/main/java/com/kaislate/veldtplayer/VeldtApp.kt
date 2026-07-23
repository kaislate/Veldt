package com.kaislate.veldtplayer

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ealvatag.tag.TagOptionSingleton
import javax.inject.Inject

@HiltAndroidApp
class VeldtApp : Application(), Configuration.Provider {

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
}
