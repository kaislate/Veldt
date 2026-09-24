// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.VeldtUri

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
 * The design spec's `(sourceId, externalId, size)` art keying (§5.6) is what [remoteRef] below
 * derives, rather than a field this class carries directly: a remote row's [uri] already IS
 * `veldt://track/<sourceId>/<externalId>`, so a second field would only duplicate it and risk
 * disagreeing.
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

/**
 * The [TrackRef] this row streams from, or null for a local (MediaStore) row.
 *
 * A remote row's [SongArt.uri] IS its logical `veldt://track/…` uri (spec §5.6) — the same one
 * enqueued for playback — so this is a pure re-parse of it, not a new identity. [ArtSourcePlan]
 * uses this to decide the whole plan: a non-null [remoteRef] means the local rungs (MediaStore
 * thumbnail, embedded tag) do not apply at all, because neither a `content://` uri nor a local
 * file path exists for a row that was never synced onto this device.
 */
val SongArt.remoteRef: TrackRef? get() = VeldtUri.parse(uri)
