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
     * 1. [Song.relativeKey] — `VOLUME_NAME + RELATIVE_PATH + DISPLAY_NAME`. Guaranteed from API 29
     *    (this app's floor) and the non-deprecated replacement for `DATA`, so it is the one to
     *    prefer. Volume-qualified, so it does not collide across storage volumes.
     * 2. [Song.filePath] — the `DATA` path. Second rung rather than first because providers may
     *    withhold it, but it is an absolute path including the mount point — therefore already
     *    volume-qualified — and is what the tag reader already relies on.
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
            // The non-deprecated location triple (API 29+). Together they compose the
            // rescan-stable playlist key, which DATA cannot be relied on to provide. VOLUME_NAME
            // is required, not optional: RELATIVE_PATH is volume-relative and this query spans
            // volumes, so without it two files at the same path on internal and SD collide.
            MediaStore.Audio.Media.VOLUME_NAME,
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
            val volumeIx = c.getColumnIndex(MediaStore.Audio.Media.VOLUME_NAME)
            val relPathIx = c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
            val displayIx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(idIx)
                val rawTrack = if (c.isNull(trackIx)) 0 else c.getInt(trackIx)
                out += Song(
                    // NOT the MediaStore `_ID`. `Song.id` is a Room-assigned surrogate and this
                    // method is reading a *source*, not the database — it has no way to know one,
                    // and inventing a plausible-looking number here is exactly how the old
                    // "`Song.id` means the `_ID`" assumption would survive the flip. The scan
                    // assigns the real id when `SongDao.upsertBySourceKey` persists the row; the
                    // source's own identity for the track travels in `externalId` below.
                    id = Song.UNSAVED,
                    // The val, never the literal (Global Constraint 1) — the initializer of `id`
                    // above is the single place that string is written anywhere in `src/main`
                    // (`SourceIdLiteralTest` proves it), and reading it back here is what makes a
                    // renamed source propagate instead of silently disagreeing.
                    sourceId = this@LocalSource.id,
                    // The MediaStore `_ID` is this source's native identity for the track. It is
                    // deliberately carried separately from `Song.id`: the two hold the same number
                    // today only because the PK flip has not landed yet.
                    externalId = id.toString(),
                    uri = ContentUris.withAppendedId(base, id).toString(),
                    filePath = if (c.isNull(dataIx)) null else c.getString(dataIx),
                    relativeKey = composeRelativeKey(
                        volumeName = if (volumeIx >= 0) c.getString(volumeIx) else null,
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
        /** Separates the volume from the volume-relative path. See [composeRelativeKey]. */
        private const val VOLUME_SEPARATOR = ":"

        /**
         * ## The key uniqueness invariant — read this before changing any rung
         *
         * A playlist key must be unique **across all four** of the following, and stable under the
         * fourth. Four defects in this task were each a rung that satisfied three and quietly
         * missed one, so check a candidate key against every line, not the one you are thinking
         * about:
         *
         * 1. **Volume** — internal storage vs. removable SD. [listSongs] queries `VOLUME_EXTERNAL`,
         *    which spans both, so a volume-relative key collides. (Missed in round 3.)
         * 2. **Directory** — `Music/a.mp3` vs. `Podcasts/a.mp3`, and `Music` vs. `" Music"`, which
         *    are genuinely different folders on ext4/f2fs. A key that normalises them together
         *    merges real directories. (Missed by an over-broad `trim()`.)
         * 3. **Filename** — including leading/trailing whitespace, which is legal on ext4/f2fs
         *    (illegal only on FAT/exFAT, i.e. the SD card). `" a.mp3"` and `"a.mp3"` can coexist in
         *    one folder, so `DISPLAY_NAME` is used verbatim. (Missed by the same `trim()`.)
         * 4. **MediaStore `_ID` generation** — the key must NOT change when a rescan reissues the
         *    id for an unchanged file, which is the entire reason this function exists rather than
         *    using [Song.uri]. (Missed in round 1.)
         *
         * A rung that fails 1–3 returns the WRONG track and then writes the wrong `songId` back,
         * corrupting the cache on a read path. A rung that fails 4 returns nothing and the entry
         * goes blank. The first is much worse, so when a part is missing this function returns null
         * and lets the caller fall through to an absolute path rather than emitting a partial key.
         *
         * ### An assumption this rests on that is not visible in this file
         *
         * **MediaStore `_ID` is unique across external volumes.** [com.kaislate.veldtplayer.data.library.db.SongEntity]'s
         * primary key is that id, so if two volumes could reuse one id, the internal and SD rows
         * would collapse into a single Room row *before* [stableKey] ever ran — and volume-
         * qualifying the key would be decorative. This holds on API 29+ (one `external.db` with
         * `volume_name` as a column, not a database per volume), but nothing here enforces it and
         * it is unobservable from Robolectric, because the fixtures supply the rows. If a future
         * source ever shares the `songs` table with per-volume id spaces, that is the thing that
         * breaks first, silently, and no test in this repo would notice.
         *
         * ### Note for Task 4 (the deeper resolution ladder)
         *
         * All rungs currently share ONE flat key space: `PlaylistRepository.resolve` builds a
         * single `associateBy { stableKey(it) }`. Today they happen not to overlap — a rung-1 key
         * never starts with `/`, a `DATA` path always does, and a uri always starts with
         * `content://` — but that is luck, not design. A title/artist/duration rung has no such
         * discipline and could collide with a filename. **Task 4 needs namespaced rung prefixes**
         * (e.g. `rel:`, `data:`, `tag:`), not merely rungs that are each individually correct.
         *
         * ---
         *
         * Compose `VOLUME_NAME` + `RELATIVE_PATH` + `DISPLAY_NAME` into one fully-qualified key,
         * e.g. `external_primary` + `Music/Beck/` + `Lost Cause.mp3` →
         * `external_primary:Music/Beck/Lost Cause.mp3`.
         *
         * **The volume is not decoration.** `RELATIVE_PATH` is *volume-relative*, while
         * [listSongs] queries `EXTERNAL_CONTENT_URI` — `VOLUME_EXTERNAL`, which spans primary
         * storage and removable SD on API 29+. A user with `Music/a.mp3` on internal storage and a
         * copy at `Music/a.mp3` on an SD card would otherwise produce one key for two distinct
         * rows. `PlaylistRepository.resolve` builds `associateBy { stableKey(it) }`, which silently
         * keeps the last colliding row, so rung 1 could return the wrong file — and then write the
         * wrong `songId` back, corrupting the cache permanently on a *read* path, where the user
         * never took an action they could associate with the damage.
         *
         * `RELATIVE_PATH` conventionally carries a trailing separator and may carry a leading one,
         * so both ends are trimmed and exactly one separator is inserted — otherwise the same file
         * could key as `Music/Beck//Lost.mp3` on one device and `Music/Beck/Lost.mp3` on another,
         * and a playlist would stop resolving after a provider changed its mind about the slash.
         *
         * Returns null unless ALL THREE parts are present, so the caller falls through to the
         * `DATA` path — which is an absolute path including the mount point, and therefore already
         * volume-qualified. Emitting a partial key would defeat the point: an unqualified key is
         * precisely the thing that collides, so it must never be the fallback.
         *
         * `VOLUME_NAME` is lowercase alphanumeric with `_`/`-` (`external_primary`, `1234-5678`)
         * and never contains [VOLUME_SEPARATOR], so volume and path cannot alias into each other.
         */
        internal fun composeRelativeKey(
            volumeName: String?,
            relativePath: String?,
            displayName: String?,
        ): String? {
            // Volume: whitespace-trimmed. It is a MediaStore identifier, not a user-authored
            // name, so padding on it is noise and can never be meaningful.
            val volume = volumeName?.trim().orEmpty()
            // Directory: ONLY separators trimmed, never whitespace. A folder named " Music" is a
            // different folder from "Music" on ext4/f2fs, and normalising them together would
            // merge two real directories into one key.
            val dir = relativePath?.trim('/').orEmpty()
            // Filename: verbatim. DISPLAY_NAME is the literal name on disk and MediaStore does not
            // pad it, so trimming buys nothing and silently merges " a.mp3" with "a.mp3".
            val name = displayName.orEmpty()
            if (volume.isEmpty() || dir.isEmpty() || name.isEmpty()) return null
            // Structural, not documentary: a volume name we cannot encode unambiguously is not a
            // volume name we may use. Falling through to the absolute DATA path is the right
            // answer, and this is one line rather than a KDoc claim nothing checks.
            if (volume.contains(VOLUME_SEPARATOR)) return null
            // The twin of the guard above, and it exists for the same reason. The injectivity
            // proof leans on "the LAST '/' is unambiguously the directory boundary", which holds
            // only while a filename contains no '/'. That is true today — '/' is illegal in a
            // POSIX filename and MediaProvider sanitises supplied display names — but it was
            // equally true of ':' in VOLUME_NAME right up until it was hardened. Without this,
            // ("Music/Beck", "Lost.mp3") and ("Music", "Beck/Lost.mp3") produce one key.
            if (name.contains('/')) return null
            return "$volume$VOLUME_SEPARATOR$dir/$name"
        }
    }
}
