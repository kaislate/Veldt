// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.model

/** Framework-free library domain models. `uri` is a String so pure code/tests
 *  never import android.net.Uri; the UI parses it at the play call site. */
data class Song(
    /**
     * The app-internal `Long` handle for this track: the Room primary key, the Media3 `mediaId`,
     * and the key playlist entries cache. It is opaque — **nothing may parse meaning out of it**.
     * In particular it is not a MediaStore `_ID`, no `content://` uri may be derived from it, and
     * it is never compared to an [externalId]. (It still happens to hold the MediaStore `_ID` for
     * local rows as this is written, but that is an implementation accident on its way out: it
     * becomes a Room-assigned surrogate, and any code written against the old meaning breaks
     * silently rather than loudly when it does. Use [externalId] when you mean the source's id.)
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
)

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
