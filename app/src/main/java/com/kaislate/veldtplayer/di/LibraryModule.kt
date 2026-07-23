package com.kaislate.veldtplayer.di

import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.LocalSource
import com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader
import com.kaislate.veldtplayer.data.library.tag.TagReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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
}
