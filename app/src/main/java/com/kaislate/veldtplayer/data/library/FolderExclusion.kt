// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * "Hide from library": which local songs the tag views leave out, and which folders the Folders
 * tab marks (Step 5 spec §4).
 *
 * **A key is a [FolderNode.key]** — `volume:seg/seg`, or the bare volume for a volume root, as
 * [FolderTree.folderKey] composes it. No second identity is defined, for three reasons:
 * - It is what the row being long-pressed already carries, so hiding needs no translation.
 * - It is a function of the path alone (volume + segments), never of a MediaStore `_ID` or a Room
 *   surrogate, so it survives any rescan that leaves the file where it was.
 * - It is derivable from [Song.location] by the same function, so a song and the folder it sits in
 *   agree on the key without the tree being built.
 *
 * It is also unambiguous to parse back, which the matching below depends on: the volume never
 * contains `:` (`LocalSource.composeRelativeKey` rejects one, and a `/storage/<id>/` volume is a
 * single path component), and no segment contains `/` because segments are what [PathSegments]
 * split on it. So the FIRST `:` ends the volume, and a segment is free to contain `:` itself.
 *
 * **Matching is on whole segments, never on the string.** `Music/A` covers `Music/A/B` and does not
 * cover `Music/AB`, which a `startsWith` on the key would. A key covers its own folder and every
 * folder below it, so hiding a volume root hides the whole volume.
 *
 * **Only local songs are ever excluded.** A server song's `relativeKey` is the SERVER's path, which
 * [Song.location] will happily parse into something volume-shaped when it contains a `:` — so the
 * source check here is not defensive, it is the only thing between a Navidrome path like
 * `Live: 1999/a.flac` and a local exclusion of a folder that happens to be called `Live`.
 *
 * A song with no location (the Unfiled bucket) is never excluded: there is no folder to hide it by,
 * and the NUL-prefixed [UNFILED_KEY] cannot be parsed into one.
 *
 * Built once per set of keys and then asked per song and per node, so the keys are parsed once
 * rather than once per song.
 */
class FolderExclusion(keys: Set<String>, private val localSourceId: String) {

    private val scopes: List<Scope> = keys.mapNotNull(::parse)

    /** No key parsed — the common case, and the one that lets callers skip filtering entirely. */
    val isEmpty: Boolean get() = scopes.isEmpty()

    /** True when [song] is local and sits in, or below, an excluded folder. */
    fun isExcluded(song: Song): Boolean {
        if (scopes.isEmpty() || song.sourceId != localSourceId) return false
        val location = song.location() ?: return false
        return covers(location.volume, location.segments)
    }

    /** True when [node] is an excluded folder or sits below one. The synthetic nodes never are. */
    fun isHidden(node: FolderNode): Boolean = covers(node.volume, node.segments)

    /** As [isHidden], for a folder the tree is still building. */
    fun covers(volume: String, segments: List<String>): Boolean = scopes.any { scope ->
        scope.volume == volume &&
            segments.size >= scope.segments.size &&
            segments.subList(0, scope.segments.size) == scope.segments
    }

    private class Scope(val volume: String, val segments: List<String>)

    companion object {
        /** A folder with no parsed form: a synthetic node, or a stored key this build cannot read. */
        private fun parse(key: String): Scope? {
            if (key.isEmpty() || key.startsWith('\u0000')) return null
            val separator = key.indexOf(LocalSource.VOLUME_SEPARATOR)
            if (separator < 0) return Scope(key, emptyList())
            if (separator == 0) return null
            val segments = key.substring(separator + 1).split('/')
            // [FolderTree.folderKey] never writes an empty segment; a key carrying one was not
            // written by it, and guessing what it meant could hide the wrong folder.
            if (segments.any { it.isEmpty() }) return null
            return Scope(key.substring(0, separator), segments)
        }

        /** Whether [key] names a folder that can be hidden at all. */
        fun isFolderKey(key: String): Boolean = parse(key) != null

        /**
         * [key] as a person reads it: `SD card › BACKUP › Downloads`, the breadcrumb's shape, with
         * the volume named by [volumeLabel] so a renamed volume reads by its new name here too.
         *
         * A key that does not parse is shown as stored rather than dropped: it is still in the list,
         * and the user has to be able to see what they are removing.
         */
        fun describe(key: String, volumeLabel: (String) -> String): String {
            val scope = parse(key) ?: return key
            return (listOf(volumeLabel(scope.volume)) + scope.segments).joinToString(" › ")
        }
    }
}
