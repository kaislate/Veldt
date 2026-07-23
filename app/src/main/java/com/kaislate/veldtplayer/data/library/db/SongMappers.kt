package com.kaislate.veldtplayer.data.library.db

import com.kaislate.veldtplayer.data.library.model.Song

fun SongEntity.toDomain(): Song = Song(
    id = id, uri = uri, filePath = filePath, title = title, artist = artist, album = album,
    albumArtist = albumArtist, trackNumber = trackNumber, discNumber = discNumber, year = year,
    durationMs = durationMs, dateModifiedSec = dateModifiedSec, hasEmbeddedArt = hasEmbeddedArt,
)

fun Song.toEntity(): SongEntity = SongEntity(
    id = id, uri = uri, filePath = filePath, title = title, artist = artist, album = album,
    albumArtist = albumArtist, trackNumber = trackNumber, discNumber = discNumber, year = year,
    durationMs = durationMs, dateModifiedSec = dateModifiedSec, hasEmbeddedArt = hasEmbeddedArt,
)
