package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * The grouping identity for albums and artists. Tags in real libraries are messy —
 * "Beatles", "beatles" and "BEATLES " are one artist to a human and must be one row
 * in the UI. The normalized form is also the navigation route parameter, so casing
 * variants resolve to the same destination.
 *
 * Display names keep their original spelling; only the KEY is folded.
 *
 * **Callers must `Uri.encode` a key before splicing it into a path segment.** A key is
 * derived from free-text tags, so it can legally contain `/` and `%` — the band AC/DC
 * normalizes to `ac/dc`, which would otherwise read as two segments and fail to match
 * `artist/{key}`. Keys are never blank (see [UNKNOWN_ALBUM] / [UNKNOWN_ARTIST]), because
 * an empty segment would build `album/` and match nothing either.
 *
 * Every key is a fixed point of [normalize] — `normalize(albumKey(s)) == albumKey(s)` —
 * so a caller that defensively re-normalizes a key it received still matches.
 */
object LibraryKeys {

    /**
     * Delimiter between the fields of a compound key (ASCII UNIT SEPARATOR). It has to be a
     * character that cannot occur in a normalized tag: with a space, `"Bob"` + `"Dylan Live"`
     * and `"Bob Dylan"` + `"Live"` both key to `bob dylan live`, merging two unrelated albums
     * into one tile and one detail screen.
     *
     * Note U+001F *is* whitespace to the JVM (`Character.isWhitespace` covers U+001C..U+001F),
     * so [String.trim] strips it from a key's EDGES — which is exactly what should happen when
     * a field is blank — while interior separators survive.
     */
    const val FIELD_SEPARATOR = "\u001F"

    /** Key for songs whose album tag is blank; a route segment must never be empty. */
    const val UNKNOWN_ALBUM = "unknown-album"

    /** Key for songs whose artist tag is blank. */
    const val UNKNOWN_ARTIST = "unknown-artist"

    fun normalize(value: String): String = value.trim().lowercase()

    /**
     * An album is identified by its artist AND its title, never the title alone: a library
     * holding both Queen's and ABBA's "Greatest Hits" would otherwise show a single tile
     * containing both, opening onto a detail screen that mixes two artists.
     *
     * Uses `albumArtist` when tagged, so a compilation or a record with guest features
     * stays whole, and falls back to the track artist when it is absent.
     */
    fun albumKey(song: Song): String = albumKey(song.album, song.albumArtist, song.artist)

    fun albumKey(album: String, albumArtist: String?, artist: String): String {
        val owner = normalize(albumArtist ?: artist)
        // Re-normalized so the compound is itself a fixed point of normalize(): a blank field
        // leaves the separator on an edge, where trim() drops it. Interior ones survive.
        return normalize("$owner$FIELD_SEPARATOR${normalize(album)}").ifBlank { UNKNOWN_ALBUM }
    }

    fun artistKey(song: Song): String = artistKey(song.artist)

    fun artistKey(artist: String): String = normalize(artist).ifBlank { UNKNOWN_ARTIST }
}
