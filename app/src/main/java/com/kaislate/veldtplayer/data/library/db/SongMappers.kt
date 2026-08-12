// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import com.kaislate.veldtplayer.data.library.model.Song

fun SongEntity.toDomain(): Song = Song(
    id = id, sourceId = sourceId, externalId = externalId,
    uri = uri, filePath = filePath, relativeKey = relativeKey,
    title = title, artist = artist, album = album,
    albumArtist = albumArtist, trackNumber = trackNumber, discNumber = discNumber, year = year,
    durationMs = durationMs, dateModifiedSec = dateModifiedSec, hasEmbeddedArt = hasEmbeddedArt,
)

fun Song.toEntity(): SongEntity = SongEntity(
    id = id, sourceId = sourceId, externalId = externalId,
    uri = uri, filePath = filePath, relativeKey = relativeKey,
    title = title, artist = artist, album = album,
    albumArtist = albumArtist, trackNumber = trackNumber, discNumber = discNumber, year = year,
    durationMs = durationMs, dateModifiedSec = dateModifiedSec, hasEmbeddedArt = hasEmbeddedArt,
)
