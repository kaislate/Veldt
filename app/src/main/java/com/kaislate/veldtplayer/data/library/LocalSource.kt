package com.kaislate.veldtplayer.data.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist
import com.kaislate.veldtplayer.data.library.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The P1 [LibrarySource]: enumerates on-device audio via `MediaStore.Audio.Media`
 * (music only). Emits **MediaStore-derived** [Song]s — no tag parsing here; the
 * scanner (Task 6) augments each row via [com.kaislate.veldtplayer.data.library.tag.EAlvaTagReader]
 * using the surfaced [Song.filePath].
 *
 * Assumes read-audio permission is already granted (the runtime request lives in the
 * UI, Task 7). If the query is denied or returns null/empty, [listSongs] returns an
 * empty list — it never throws, so a denied scan degrades to "no library" rather than
 * a crash. Column reads are guarded (null-safe; `ALBUM_ARTIST` may be absent per-OEM).
 */
class LocalSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : LibrarySource {

    override val id: String = "local"

    override fun resolvePlayableUri(song: Song): String = song.uri

    override suspend fun listAlbums(): List<Album> = LibraryDerivations.deriveAlbums(listSongs())
    override suspend fun listArtists(): List<Artist> = LibraryDerivations.deriveArtists(listSongs())

    override suspend fun search(query: String): List<Song> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return listSongs().filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q) ||
                it.album.lowercase().contains(q)
        }
    }

    override suspend fun listSongs(): List<Song> = withContext(Dispatchers.IO) {
        val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ARTIST, // may be absent on some OEMs — guarded below
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DATA, // file path for the tag reader; nullable on API 29+
        )
        val out = ArrayList<Song>()
        runCatching {
            context.contentResolver.query(
                base,
                cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
            )?.use { c ->
            val idIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumArtistIx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST) // -1 if absent
            val trackIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val durIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val modIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val dataIx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idIx)
                val rawTrack = if (c.isNull(trackIx)) 0 else c.getInt(trackIx)
                out += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    filePath = if (c.isNull(dataIx)) null else c.getString(dataIx),
                    title = c.getString(titleIx) ?: "Unknown",
                    artist = c.getString(artistIx) ?: "Unknown artist",
                    album = c.getString(albumIx) ?: "Unknown album",
                    albumArtist = if (albumArtistIx >= 0 && !c.isNull(albumArtistIx)) {
                        c.getString(albumArtistIx)
                    } else {
                        null
                    },
                    trackNumber = (rawTrack % 1000).takeIf { it > 0 },
                    discNumber = (rawTrack / 1000).takeIf { it > 0 },
                    year = if (c.isNull(yearIx)) null else c.getInt(yearIx).takeIf { it > 0 },
                    durationMs = if (c.isNull(durIx)) 0L else c.getLong(durIx),
                    dateModifiedSec = if (c.isNull(modIx)) 0L else c.getLong(modIx),
                    hasEmbeddedArt = false,
                )
            }
        }
            out
        }.getOrElse {
            // Query itself threw (e.g. SecurityException on revoked audio-read, or an
            // OEM/provider that raises instead of returning null). Degrade to "no library".
            Log.w("LocalSource", "audio enumeration failed; returning empty list", it)
            emptyList()
        }
    }
}
