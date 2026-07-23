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
    /** Stable source id, e.g. "local". */
    val id: String

    suspend fun listSongs(): List<Song>
    suspend fun listAlbums(): List<Album>
    suspend fun listArtists(): List<Artist>
    suspend fun search(query: String): List<Song>

    /** Resolve the string a MediaItem should play. Local: the content:// uri. */
    fun resolvePlayableUri(song: Song): String
}
