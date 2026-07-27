package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * What to call a track, a record or an artist when the tags will not say — in ONE place.
 *
 * There are two ways a tag can carry no information and only one of them is obvious.
 *
 * 1. It is absent, empty, or whitespace. Every screen already handled this, each with its
 *    own `?: / .trim() / .ifBlank { }` chain.
 * 2. **It is MediaStore's literal sentinel, `<unknown>`.** MediaStore substitutes that
 *    STRING for a missing artist or album rather than returning null, and it is not blank,
 *    so every `isBlank()` check in the app sailed straight past it. Rows read
 *    `<unknown> · Download`, tiles were captioned `<unknown>` — and, far worse,
 *    [LibraryKeys] grouped every untagged file in the library under that one bogus name,
 *    collapsing the whole Artists tab into a single entry.
 *
 * Both cases now mean the same thing to the whole app, which is only true because there is
 * one definition of "missing" ([isMissing]) rather than seven.
 */
object DisplayNames {

    const val UNKNOWN_TITLE = "Unknown title"
    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_ARTIST = "Unknown artist"

    /**
     * MediaStore's stand-in for an absent tag. Kept as a literal rather than referencing
     * `android.provider.MediaStore.UNKNOWN_STRING` so this file stays framework-free and
     * JVM-testable; [LocalSource] checks the platform constant too, so an OEM that ever
     * changed it would still be caught at the boundary.
     */
    const val MEDIASTORE_UNKNOWN = "<unknown>"

    /** True when a tag carries no information: absent, blank, or MediaStore's sentinel. */
    fun isMissing(value: String?): Boolean {
        val trimmed = value?.trim() ?: return true
        return trimmed.isEmpty() || trimmed.equals(MEDIASTORE_UNKNOWN, ignoreCase = true)
    }

    /** The tag's own text, trimmed — or null when it says nothing. */
    fun tagOrNull(value: String?): String? = value?.trim()?.takeUnless { isMissing(it) }

    fun title(value: String?): String = tagOrNull(value) ?: UNKNOWN_TITLE

    fun album(value: String?): String = tagOrNull(value) ?: UNKNOWN_ALBUM

    fun artist(value: String?): String = tagOrNull(value) ?: UNKNOWN_ARTIST

    /**
     * The name a RECORD is filed under: its album artist when tagged, otherwise the track
     * artist — the same choice [LibraryKeys.albumKey] groups by, so a tile's caption always
     * names the owner it was keyed under. Three screens carried a hand-rolled copy of this.
     */
    fun albumArtist(albumArtist: String?, artist: String?): String =
        tagOrNull(albumArtist) ?: artist(artist)
}

fun Song.displayTitle(): String = DisplayNames.title(title)

fun Song.displayArtist(): String = DisplayNames.artist(artist)

fun Song.displayAlbum(): String = DisplayNames.album(album)

fun Song.displayAlbumArtist(): String = DisplayNames.albumArtist(albumArtist, artist)
