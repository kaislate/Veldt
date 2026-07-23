package com.kaislate.veldtplayer.data.library.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: Long,
    val uri: String,
    val filePath: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val dateModifiedSec: Long,
    val hasEmbeddedArt: Boolean,
)

/** Lightweight projection for scan-diff (avoids loading full rows). */
data class IndexEntry(val id: Long, val dateModifiedSec: Long)
