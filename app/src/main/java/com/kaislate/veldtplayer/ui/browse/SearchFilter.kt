package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.LibraryKeys
import com.kaislate.veldtplayer.data.library.model.Album
import com.kaislate.veldtplayer.data.library.model.Artist

/**
 * Which records and which artists a search term NAMES.
 *
 * Both tests run against the entity's [Album.key] / [Artist.key] rather than its display
 * name, and that is the whole trick. A key is already the folded form of the tags the
 * library groups by — lowercased, trimmed, with MediaStore's `<unknown>` sentinel resolved
 * — so a plain `contains` over it is a case- and whitespace-insensitive match without a
 * second normalization rule to keep in step with [LibraryKeys]. For an album the key is
 * compound (owner + title), so ONE containment check covers both "abbey road" and
 * "beatles", including the case where the record carries no ALBUM_ARTIST tag and its owner
 * came from the track artist.
 *
 * Pure and framework-free, so the matching rule is unit-testable without a ViewModel.
 */
internal object SearchFilter {

    fun albums(all: List<Album>, term: String): List<Album> =
        filterByKey(all, term) { it.key }

    fun artists(all: List<Artist>, term: String): List<Artist> =
        filterByKey(all, term) { it.key }

    /**
     * A blank term matches NOTHING, never everything: search sections exist to answer a
     * question, and an unasked question has no answer. The term is folded through
     * [LibraryKeys.normalize] so it is compared in the same alphabet as the keys.
     */
    private inline fun <T> filterByKey(all: List<T>, term: String, key: (T) -> String): List<T> {
        val needle = LibraryKeys.normalize(term)
        return if (needle.isEmpty()) emptyList() else all.filter { key(it).contains(needle) }
    }
}
