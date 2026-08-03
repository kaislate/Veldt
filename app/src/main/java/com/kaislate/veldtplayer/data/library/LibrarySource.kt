// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The library extension point (spec §3.2). P1 has one implementation, [LocalSource].
 * Framework-free by contract: playable references are Strings, not android.net.Uri,
 * so this interface and its pure consumers stay JVM-testable.
 */
interface LibrarySource {
    /**
     * Stable source id, e.g. `local` for [LocalSource].
     *
     * The **only** place a source's id may be written is its own implementation's initializer
     * (Global Constraint 1); everything else reads it from here or from `Song.sourceId`. A
     * `SourceIdLiteralTest` enforces that structurally, which is also why this line spells the id in
     * backticks rather than as a Kotlin string literal.
     */
    val id: String

    suspend fun listSongs(): List<Song>
    suspend fun listAlbums(): List<Album>
    suspend fun listArtists(): List<Artist>
    suspend fun search(query: String): List<Song>

    /** Resolve the string a MediaItem should play. Local: the content:// uri. */
    fun resolvePlayableUri(song: Song): String

    /**
     * Stable identity for playlist membership. Must NOT embed anything a rescan can change.
     *
     * Deliberately separate from [resolvePlayableUri]: they answer different questions, and
     * conflating them is a real defect, not a style point. The local playable uri is
     * `content://media/external/audio/media/<MediaStore _ID>` — keyed on the very id that a
     * rescan reissues when a file moves or a volume remounts. A playlist entry keyed on it would
     * fail to re-resolve in exactly the scenario re-resolution exists for, so
     * [com.kaislate.veldtplayer.data.playlist.PlaylistRepository] stores this instead.
     *
     * A future remote source returns its server-side GUID here and needs no other change.
     */
    fun stableKey(song: Song): String
}
