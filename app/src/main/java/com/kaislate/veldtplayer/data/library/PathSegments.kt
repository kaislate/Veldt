// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

/**
 * Splitting a path into segments, and the ONE place the rules for doing it live.
 *
 * Two rules, both load-bearing, both of which this repo has lost rounds to:
 *
 * 1. **Only separators are trimmed. Never whitespace.** `" Music"` and `"Music"` are two real
 *    directories on ext4/f2fs, and `" a.mp3"` and `"a.mp3"` two real files. Trimming here merges
 *    them and the merge is invisible until someone has such a directory.
 * 2. **No case folding.** Folder identity is byte-exact (global constraint 7). Case is folded for
 *    ORDERING only, in [FolderSort], and never for a key.
 *
 * Device-observed 2026-08-14 and the reason this is a function rather than a `split('/')` at each
 * call site: MediaStore records `RELATIVE_PATH == "/"` for a file at the volume root. A bare
 * `"/".split('/')` is `["", ""]`, which would put two blank-named folders at the root of the tree.
 *
 * `LocalEntryResolver.canonicalise` calls this too, so the non-fold rules have one home rather
 * than two. It keeps its own URI-decoding, `..`-resolution and relative-joining, which are M3U
 * concerns a MediaStore path does not have — that is why this primitive is narrow and that
 * function was not lifted wholesale.
 */
object PathSegments {

    /** [path] split on `/`, with empty segments dropped and segment contents untouched. */
    fun split(path: String): List<String> =
        path.split('/').filter { it.isNotEmpty() }
}
