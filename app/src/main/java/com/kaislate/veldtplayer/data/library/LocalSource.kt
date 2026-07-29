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

    /**
     * A key that survives a rescan reissuing the MediaStore `_ID`. Three rungs, in order:
     *
     * 1. [Song.relativeKey] — `RELATIVE_PATH + DISPLAY_NAME`. Guaranteed from API 29 (this app's
     *    floor) and the non-deprecated replacement for `DATA`, so it is the one to prefer.
     * 2. [Song.filePath] — the `DATA` path. Second rung rather than first because providers may
     *    withhold it, but it is fully qualified when present and is what the tag reader already
     *    relies on.
     * 3. [Song.uri] — a genuine last resort, and the only rung that embeds `_ID`. An entry keyed
     *    here does NOT survive a rescan; see the report's R3-C1. It is still better than throwing
     *    or keying on null, and with rung 1 available from the minSdk it should be unreachable in
     *    practice.
     */
    override fun stableKey(song: Song): String =
        song.relativeKey ?: song.filePath ?: song.uri

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
            // The non-deprecated location pair (API 29+). Together they compose the rescan-stable
            // playlist key, which DATA cannot be relied on to provide.
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
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
            // Guarded like ALBUM_ARTIST: these are contractually present from API 29, but OEM
            // providers have historically dropped columns, and a missing one must degrade the key
            // rather than throw mid-enumeration.
            val relPathIx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val displayIx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIx)
                val rawTrack = if (c.isNull(trackIx)) 0 else c.getInt(trackIx)
                out += Song(
                    id = id,
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    filePath = if (c.isNull(dataIx)) null else c.getString(dataIx),
                    relativeKey = composeRelativeKey(
                        relativePath = if (relPathIx >= 0) c.getString(relPathIx) else null,
                        displayName = if (displayIx >= 0) c.getString(displayIx) else null,
                    ),
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

    companion object {
        /**
         * Compose `RELATIVE_PATH` + `DISPLAY_NAME` into one volume-relative key, e.g.
         * `Music/Beck/` + `Lost Cause.mp3` → `Music/Beck/Lost Cause.mp3`.
         *
         * `RELATIVE_PATH` conventionally carries a trailing separator and may carry a leading one,
         * so both ends are trimmed and exactly one separator is inserted — otherwise the same file
         * could key as `Music/Beck//Lost.mp3` on one device and `Music/Beck/Lost.mp3` on another,
         * and a playlist would stop resolving after a provider changed its mind about the slash.
         *
         * Returns null unless BOTH parts are present. A bare display name is deliberately NOT a
         * key: `EXTERNAL_CONTENT_URI` spans volumes on API 29+, so `Lost.mp3` alone would collide
         * across directories and could resolve an entry to the wrong file — worse than falling
         * through to the fully-qualified `DATA` path on the next rung.
         */
        internal fun composeRelativeKey(relativePath: String?, displayName: String?): String? {
            val name = displayName?.trim().orEmpty()
            val dir = relativePath?.trim()?.trim('/').orEmpty()
            if (name.isEmpty() || dir.isEmpty()) return null
            return "$dir/$name"
        }
    }
}
