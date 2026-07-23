package com.kaislate.veldtplayer.data.library.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [SongEntity::class], version = 1, exportSchema = false)
abstract class VeldtDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao

    companion object { const val NAME = "veldt-library.db" }
}
