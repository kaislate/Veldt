// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.model

/** Framework-free library domain models. `uri` is a String so pure code/tests
 *  never import android.net.Uri; the UI parses it at the play call site. */
data class Song(
    /**
     * The app-internal `Long` handle for this track: the Room primary key, the Media3 `mediaId`,
     * and the key playlist entries cache. It is a **Room-assigned surrogate** and it is opaque —
     * **nothing may parse meaning out of it**. It is not a MediaStore `_ID`, no `content://` uri
     * may be derived from it, and it is never compared to an [externalId]. Use [externalId] when
     * you mean the source's own id for the track.
     *
     * The id space is shared by every source, which is the point: it is the one handle the player,
     * the playlist cache and the art cache can all hold without knowing which source a track came
     * from. It is assigned by `SongDao.upsertBySourceKey` and by nothing else.
     *
     * **A [Song] that a source merely *enumerated* has no surrogate yet** and carries [UNSAVED].
     * `LibrarySource.listSongs` cannot know one — it is reading a source, not the database — so
     * every consumer of `listSongs` must treat this field as absent. See [UNSAVED].
     */
    val id: Long,
    /** The owning [com.kaislate.veldtplayer.data.library.LibrarySource]'s `id`. Half of the
     *  cross-source identity `(sourceId, externalId)`, which is what actually names a track. */
    val sourceId: String,
    /**
     * The track's identity **as its own source names it** — for the local source the MediaStore
     * `_ID` rendered as a string; for a server source, that server's GUID. Unique only within a
     * [sourceId]: two sources may hand out the same string for different songs, which is exactly
     * why the pair and not this field alone is the identity.
     */
    val externalId: String,
    val uri: String,            // content:// playable uri, as String
    val filePath: String?,      // MediaStore DATA path for tag reading; null for remote sources
    /**
     * `VOLUME_NAME + RELATIVE_PATH + DISPLAY_NAME`, e.g.
     * `external_primary:Music/Beck/Lost Cause.mp3` — the fully-qualified location of the file.
     * Present from API 29 (this app's floor) and the non-deprecated replacement for [filePath],
     * which providers may withhold.
     *
     * This is the preferred playlist identity: unlike [uri] it embeds no MediaStore `_ID`, so it
     * survives a rescan reissuing one. The volume is part of the key because `RELATIVE_PATH` alone
     * is volume-relative while the library query spans volumes — see
     * `LocalSource.composeRelativeKey`. Null for remote sources, and null whenever any of the
     * three parts is missing (a partial key would collide, so it is never emitted).
     */
    val relativeKey: String?,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val dateModifiedSec: Long,  // MediaStore DATE_MODIFIED (seconds) — scan-diff change key
    val hasEmbeddedArt: Boolean,
) {
    companion object {
        /**
         * A [Song] a source enumerated but that has not been persisted: Room has not assigned its
         * surrogate [id] yet. `LibrarySource.listSongs` emits this for every row, because a source
         * describes its own library and has never heard of this app's id space.
         *
         * **It must never be written into any cache** — not `playlist_entries.songId` (see
         * `PlaylistImporter.newEntryOf` and `PlaylistRepository.addSongs`), not an art or palette
         * cache. `null` is the correct thing to store instead, and it is a genuinely different
         * thing: `null` means "unknown, ask the resolver", while `0` claims to *be* an id and
         * pins the entry to a row that cannot exist — a permanently dead cache entry that the
         * self-healing resolution ladder then has no reason to repair. `?: 0` is the shape of
         * that bug; there is a test asserting no `0` is ever cached.
         *
         * The value is `0` rather than a louder `-1` because that is Room's own "not set" signal
         * for an `autoGenerate` primary key: the sentinel and the thing it means are the same
         * number all the way down, so a `Song` handed straight to `upsertBySourceKey` simply gets
         * an id assigned instead of needing translation. `SongEntity.id` documents the other half.
         */
        const val UNSAVED = 0L
    }
}

/** [key] is the normalized grouping identity; [name] is the first-seen spelling. */
data class Album(
    val key: String,
    val name: String,
    val albumArtist: String?,
    val songCount: Int,
)

/** [key] is the normalized grouping identity; [name] is the first-seen spelling. */
data class Artist(
    val key: String,
    val name: String,
    val albumCount: Int,
    val songCount: Int,
)
