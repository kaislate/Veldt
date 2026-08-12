// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The identity Coil loads art for. Deliberately NOT the whole [Song] — the cache key
 * is the song id, and carrying a smaller value keeps list recomposition cheap.
 *
 * **[songId] is the Room surrogate `songs.id`, and that is the right choice here** (N0 Task 6).
 * Art keys are process-lifetime caches and morph identities: all they need is that no two *live*
 * tracks share a key, which the surrogate gives directly. It is sound rather than merely
 * convenient because AUTOINCREMENT never reissues a freed id — pinned by `SongDaoTest`'s
 * `a freed surrogate id is never reissued to a later row`, so a deleted track's key cannot be
 * inherited by a different one while a stale cache entry is still live.
 *
 * The design spec's `(sourceId, externalId, size)` art keying belongs to the **remote** Coil
 * fetcher, which §5.6 defines as a parallel path arriving in N2. Building it here now would be
 * surface with no caller.
 */
data class SongArt(
    /** The Room surrogate `songs.id` — see the class KDoc for why the surrogate is correct here. */
    val songId: Long,
    val uri: String,
    val filePath: String?,
    val hasEmbeddedArt: Boolean,
)

fun Song.toSongArt() = SongArt(
    songId = id,
    uri = uri,
    filePath = filePath,
    hasEmbeddedArt = hasEmbeddedArt,
)
