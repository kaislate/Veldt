package com.kaislate.veldtplayer.data.library.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<SongEntity>)

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    fun observeAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE")
    suspend fun getAllSongs(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE album = :album ORDER BY discNumber, trackNumber")
    suspend fun getSongsByAlbum(album: String): List<SongEntity>

    @Query("SELECT * FROM songs WHERE artist = :artist ORDER BY album COLLATE NOCASE, discNumber, trackNumber")
    suspend fun getSongsByArtist(artist: String): List<SongEntity>

    @Query(
        "SELECT * FROM songs WHERE title LIKE :pattern OR artist LIKE :pattern " +
            "OR album LIKE :pattern ORDER BY title COLLATE NOCASE"
    )
    suspend fun search(pattern: String): List<SongEntity>

    @Query(
        "SELECT * FROM songs WHERE title LIKE :pattern OR artist LIKE :pattern " +
            "OR album LIKE :pattern ORDER BY title COLLATE NOCASE"
    )
    fun observeSearch(pattern: String): Flow<List<SongEntity>>

    @Query("SELECT id, dateModifiedSec FROM songs")
    suspend fun getIndex(): List<IndexEntry>

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM songs")
    suspend fun clear()
}
