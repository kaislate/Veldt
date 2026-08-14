// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.di

import android.content.Context
import androidx.room.Room
import com.kaislate.veldtplayer.data.account.db.AccountDao
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.playlist.db.PlaylistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Provides the Room database (singleton) and its DAO to the Hilt graph. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VeldtDatabase =
        Room.databaseBuilder(context, VeldtDatabase::class.java, VeldtDatabase.NAME)
            // Wipe on schema change rather than ship migrations. The songs table is a
            // disposable projection of MediaStore, so a rescan always rebuilds it — but the
            // playlist tables are user-authored and nothing can regenerate them. This is only
            // acceptable because the app is pre-release with zero users (spec §8.1).
            // BEFORE FIRST RELEASE: give the playlist AND accounts tables real migrations, or
            // move them to their own database, or the next version bump silently deletes
            // people's playlists and their configured servers. Accounts are worse than
            // playlists in one respect: dropping the row does NOT delete the sealed secret
            // file, so a destructive upgrade leaves an unreferenced encrypted password on disk.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideSongDao(db: VeldtDatabase): SongDao = db.songDao()

    @Provides
    fun providePlaylistDao(db: VeldtDatabase): PlaylistDao = db.playlistDao()

    @Provides
    fun provideAccountDao(db: VeldtDatabase): AccountDao = db.accountDao()
}
