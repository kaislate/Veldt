// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

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

    /**
     * A MediaStore tag column as text, or null when it holds nothing.
     *
     * MediaStore does not return null for a missing artist or album — it substitutes the
     * literal string `<unknown>`, which passes every `isBlank()` check in the app. Left
     * alone it reaches the UI as a caption and, worse, becomes a grouping key that files
     * the entire untagged half of a library under one imaginary artist.
     *
     * Both the platform constant and [DisplayNames.MEDIASTORE_UNKNOWN] are checked: the
     * constant is the contract, the literal is what every device has actually produced,
     * and comparing to only one of them would be trusting the wrong half of that.
     */
    private fun cleanTag(value: String?): String? =
        DisplayNames.tagOrNull(value)?.takeUnless { it.equals(MediaStore.UNKNOWN_STRING, true) }

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
                    // Tags are stored as the tags actually are — EMPTY when absent, never
                    // a display string and never MediaStore's "<unknown>" sentinel. Naming
                    // is the UI's job (DisplayNames); a data layer that invents "Unknown
                    // artist" leaves the UI unable to tell a missing tag from a band who
                    // called themselves that, and gave the library two different spellings
                    // of "no album" to sort into two different places.
                    title = cleanTag(c.getString(titleIx)).orEmpty(),
                    artist = cleanTag(c.getString(artistIx)).orEmpty(),
                    album = cleanTag(c.getString(albumIx)).orEmpty(),
                    albumArtist = if (albumArtistIx >= 0) {
                        cleanTag(c.getString(albumArtistIx))
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
