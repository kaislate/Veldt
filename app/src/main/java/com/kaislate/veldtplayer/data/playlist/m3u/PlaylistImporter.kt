// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import android.content.Context
import android.net.Uri
import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.playlist.NewPlaylistEntry
import com.kaislate.veldtplayer.data.playlist.PlaylistRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * What an import did, in the terms the user is owed: "imported 43 of 47", with the four named.
 *
 * [total] is the number of entries the file contained and the number of rows now in the playlist.
 * `total == resolved + unresolved.size` always, and both equal the row count — an import that
 * cannot match a track keeps it anyway, greyed, so that a playlist can never come back shorter
 * than the file that described it. A player that silently drops four tracks is indistinguishable
 * from one that corrupted the file.
 *
 * [unresolved] carries the parsed [M3uEntry]s themselves rather than strings, so the caller can
 * show the path *and* whatever `#EXTINF` claimed about it without re-parsing anything.
 */
data class ImportResult(
    val playlistId: Long,
    val total: Int,
    val resolved: Int,
    val unresolved: List<M3uEntry>,
)

/**
 * Imports a `.m3u`/`.m3u8` file the user picked from a document provider into a real playlist.
 *
 * This is the only impure step of the chain and it is deliberately thin: it reads bytes, hands them
 * to [M3uText], hands the text to [M3uParser], hands the entries to [LocalEntryResolver], and hands
 * the resolutions to [PlaylistRepository]. Every decision worth arguing about lives in one of those
 * four, where it is a pure function with a JVM test.
 *
 * ## The three properties this class is responsible for
 *
 * **1. Nothing is dropped.** `total` equals the parsed entry count equals the number of rows
 * written. An unresolved entry is persisted with `songId = null` under the best caption available
 * — see [captionOf] — and Task 6 renders it greyed. This is the single most important property
 * here, because the alternative failure is invisible: the user has no way to tell a playlist that
 * imported 43 of 47 from one that only ever had 43.
 *
 * **2. A failed import leaves nothing behind.** The playlist row is created *after* the file has
 * been read, decoded, parsed and resolved, so an unreadable or oversized document throws without
 * having created an empty playlist named after it. An empty but *readable* file is a different
 * thing and does create an empty playlist: the file genuinely described no tracks.
 *
 * **3. Positions stay dense.** Writes go through [PlaylistRepository.addEntries], never
 * [com.kaislate.veldtplayer.data.playlist.db.PlaylistDao.insertEntries] — the `0..n-1` invariant is
 * enforced by the repository and by nothing at schema level.
 *
 * ## Where the resolution ladder gets its library, and why it is not the Room projection
 *
 * [LibrarySource.listSongs], not `SongDao`, which is the opposite of what
 * [PlaylistRepository.resolve] does — and deliberately. `resolve` runs on every read and must use
 * the tag-merged rows the rest of the app renders; an import runs once, on an explicit user action,
 * and must work on a device where the scan has not run yet or is stale. Enumerating MediaStore
 * there costs one query and is the difference between importing a playlist and importing 47 grey
 * rows. The entries are keyed on [LibrarySource.stableKey] either way, so the first `resolve` after
 * a scan corrects any `songId` this class writes.
 */
class PlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val source: LibrarySource,
    private val playlists: PlaylistRepository,
) {

    /**
     * Import the playlist document at [uri] as a new playlist called [name].
     *
     * Throws [IOException] — including [FileNotFoundException] — if the document cannot be read or
     * exceeds [MAX_BYTES], and [SecurityException] if the permission grant has lapsed. In every
     * throwing case **no playlist is created**; there is nothing for the caller to clean up.
     */
    suspend fun import(uri: String, name: String): ImportResult {
        val bytes = readBytes(uri)
        val entries = M3uParser.parse(M3uText.decode(bytes, isM3u8 = looksLikeM3u8(uri)))
        val resolutions = LocalEntryResolver.resolve(
            entries = entries,
            library = source.listSongs(),
            playlistDir = playlistDirOf(uri),
        )

        // Only now, once the file has actually yielded something, does anything get written.
        val playlistId = playlists.create(name)
        playlists.addEntries(playlistId, resolutions.map(::newEntryOf))

        return ImportResult(
            playlistId = playlistId,
            total = resolutions.size,
            resolved = resolutions.count { it.song != null },
            unresolved = resolutions.filter { it.song == null }.map { it.entry },
        )
    }

    /**
     * One resolution as a row.
     *
     * The two branches differ in more than the null: a **resolved** entry is described by the
     * library — title, artist and album from the `Song` — because `#EXTINF` is a hint and the
     * library's own tags win wherever both exist (`LocalEntryResolver`'s rule 2). An **unresolved**
     * entry has no library row to ask, so it keeps what the playlist claimed, and its identity is
     * the playlist's own path, which is the only durable thing about it.
     */
    private fun newEntryOf(resolution: Resolution): NewPlaylistEntry {
        val song = resolution.song ?: return NewPlaylistEntry(
            sourceKey = resolution.entry.path,
            songId = null,
            title = captionOf(resolution.entry),
            artist = resolution.entry.artist.orEmpty(),
            album = "",
        )
        return NewPlaylistEntry(
            sourceKey = source.stableKey(song),
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
        )
    }

    /**
     * What to call a track nothing matched.
     *
     * `#EXTINF`'s title if there was one, else the file's own name — `Lost Cause.mp3` reads as a
     * track and `/storage/emulated/0/Music/Beck/Sea Change/Lost Cause.mp3` reads as a stack trace.
     * The full path is the last resort and is never empty, because [M3uParser] skips blank lines.
     *
     * This is display only. Identity stays the untouched path, so shortening it here cannot cost
     * the entry a match later.
     */
    private fun captionOf(entry: M3uEntry): String {
        entry.title?.let { return it }
        val name = entry.path.substringAfterLast('/').substringAfterLast('\\')
        return name.ifEmpty { entry.path }
    }

    // ------------------------------------------------------------------ the document provider

    /**
     * The document's bytes.
     *
     * [MAX_BYTES] is not a guess about playlists — 16 MB is some 80,000 entries — it is a guard
     * against what a document picker actually hands back when a user taps the wrong row. Reading a
     * 4 GB video with `readBytes()` is an OOM kill, which looks to the user like the app crashing
     * for no reason; an [IOException] here is a message they can act on.
     */
    private suspend fun readBytes(uri: String): ByteArray = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(Uri.parse(uri))
            ?: throw FileNotFoundException("no input stream for $uri")
        stream.use { it.readAtMost(MAX_BYTES) }
    }

    /** Read the whole stream, or throw rather than truncate — see [readBytes]. */
    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_CHUNK)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            // Truncating is not an option: a half-read file loses entries silently, which is the
            // one thing this whole class exists to prevent.
            if (out.size() + read > limit) throw IOException("playlist larger than $limit bytes")
            out.write(chunk, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Whether the document claims to be UTF-8 by its extension.
     *
     * Recorded and passed on; [M3uText] deliberately does not branch on it, because a file that
     * claims `.m3u8` is routinely Latin-1 and honouring the claim would break the mislabelled case.
     * Read off the raw uri text: the extension itself is never percent-encoded by any provider,
     * whatever it does to the separators around it.
     */
    private fun looksLikeM3u8(uri: String): Boolean =
        uri.substringBefore('?').substringBefore('#').endsWith(M3U8_SUFFIX, ignoreCase = true)

    /**
     * The directory the playlist file itself lives in, for resolving the relative paths that a
     * playlist exported next to its music is made of — or null when it cannot be known.
     *
     * **Null is the safe answer and is returned for everything not listed below.** A *wrong*
     * directory is far worse than no directory: with none, a bare `01 - Intro.mp3` falls to the
     * filename rung and, if two albums have one, is ambiguous and resolves to nothing. With a wrong
     * one it produces a confident [MatchStep.NORMALISED] match against some other album's track —
     * the ladder's second-strongest claim, wrong, written back as a `songId`. So this reads a
     * directory only from the two shapes that *are* a location:
     *
     *  - `file:` URIs and bare filesystem paths — the parent directory;
     *  - `com.android.externalstorage.documents`, whose document id is contractually
     *    `volume:relative/path/name.m3u`. The volume is dropped because the resolver strips it from
     *    the library side too, which is Task 4's documented cross-volume limitation and not a new
     *    one: a playlist on an SD card can claim an identically-placed file on internal storage.
     *
     * Every other authority — Downloads, Drive, a file manager's own provider — yields null. Their
     * document ids are opaque numbers or server keys, and inventing a path from one would be
     * guessing in the direction that produces wrong matches.
     */
    private fun playlistDirOf(uri: String): String? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null
        val location = when (parsed.scheme?.lowercase()) {
            null, "file" -> parsed.path
            "content" -> externalStorageLocationOf(parsed)
            else -> null
        } ?: return null
        val dir = location.trimEnd('/').substringBeforeLast('/', missingDelimiterValue = "")
        return dir.ifEmpty { null }
    }

    /**
     * The volume-relative location inside an external-storage document uri, e.g.
     * `content://com.android.externalstorage.documents/document/primary%3AMusic%2Flist.m3u` →
     * `Music/list.m3u`. Null for any other authority, and for a document id with no volume prefix.
     *
     * `lastPathSegment` is decoded by [Uri] and is the document id in both the `/document/…` and
     * the `/tree/…/document/…` forms, which is why neither is special-cased.
     */
    private fun externalStorageLocationOf(uri: Uri): String? {
        if (!EXTERNAL_STORAGE_AUTHORITY.equals(uri.authority, ignoreCase = true)) return null
        val documentId = uri.lastPathSegment ?: return null
        val volumeEnd = documentId.indexOf(VOLUME_SEPARATOR)
        if (volumeEnd < 0) return null
        return documentId.substring(volumeEnd + 1).ifEmpty { null }
    }

    private companion object {
        /** See [readBytes]. Roughly 80,000 playlist entries; not a limit any real file meets. */
        const val MAX_BYTES = 16 * 1024 * 1024
        const val DEFAULT_CHUNK = 8 * 1024
        const val M3U8_SUFFIX = ".m3u8"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val VOLUME_SEPARATOR = ':'
    }
}
