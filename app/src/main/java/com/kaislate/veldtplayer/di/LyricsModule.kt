// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import com.kaislate.veldtplayer.BuildConfig
import com.kaislate.veldtplayer.data.lyrics.LrclibCache
import com.kaislate.veldtplayer.data.lyrics.LrclibClient
import com.kaislate.veldtplayer.data.lyrics.LrclibProvider
import com.kaislate.veldtplayer.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Wires the one piece of the lyrics feature (P1.5b) that isn't already reachable through its own
 * `@Inject` constructor: [LrclibClient] and [LrclibProvider] both take a value ([LrclibClient]'s
 * `userAgent`, [LrclibProvider]'s `enabled` lambda) that only a `@Provides` method can build.
 * [com.kaislate.veldtplayer.data.lyrics.SidecarLrcProvider], [com.kaislate.veldtplayer.data.lyrics
 * .EmbeddedLyricsProvider], [com.kaislate.veldtplayer.data.lyrics.ServerLyricsProvider] and
 * [com.kaislate.veldtplayer.data.lyrics.LyricsResolver] all need nothing declared here — Dagger
 * reaches them through their own constructors.
 */
@Module
@InstallIn(SingletonComponent::class)
object LyricsModule {

    /** The app-wide [OkHttpClient] (`NetworkModule`), never mutated: [LrclibClient] builds its
     *  own 10-second-call-timeout client from it internally. */
    @Provides
    @Singleton
    fun provideLrclibClient(http: OkHttpClient): LrclibClient = LrclibClient(
        http = http,
        baseUrl = "https://lrclib.net",
        userAgent = "Veldt/${BuildConfig.VERSION_NAME} (https://github.com/kaislate/Veldt)",
    )

    /** `cacheDir/lyrics/lrclib` — OS-evictable, per spec §5's "Caching LRCLIB". */
    @Provides
    @Singleton
    fun provideLrclibCache(@ApplicationContext context: Context): LrclibCache =
        LrclibCache(File(context.cacheDir, "lyrics/lrclib"), System::currentTimeMillis)

    /** `enabled` reads the live setting on every call rather than once at injection time — a
     *  toggle in Settings must take effect on the very next lyrics open, not after a process
     *  restart. */
    @Provides
    @Singleton
    fun provideLrclibProvider(
        client: LrclibClient,
        cache: LrclibCache,
        settings: SettingsRepository,
    ): LrclibProvider = LrclibProvider(client, cache) { settings.lyricsOnline.first() }
}
