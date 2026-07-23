package com.kaislate.veldtplayer.di

import android.content.Context
import androidx.room.Room
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
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
            // The library table is a disposable projection of MediaStore; a rescan can
            // always rebuild it, so wipe on schema change rather than ship migrations.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun provideSongDao(db: VeldtDatabase): SongDao = db.songDao()
}
