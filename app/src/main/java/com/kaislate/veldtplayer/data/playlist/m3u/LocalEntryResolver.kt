// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.playlist.m3u

import com.kaislate.veldtplayer.data.library.model.Song

/**
 * Which rung of [LocalEntryResolver]'s ladder produced a match, in descending order of confidence.
 *
 * This is returned rather than kept private so that each rung is independently assertable: a test
 * can prove that [SUFFIX] matched *because* it is the suffix rung, and not because [FILENAME]
 * happened to agree. The ladder's rungs frequently agree, so "the right song came back" is not
 * evidence that the rung under test is the one that produced it.
 */
enum class MatchStep {
    /** The playlist line is, byte for byte, the song's playable uri or its `DATA` path. */
    EXACT,

    /** Same file, written differently: separators, case, `.`/`..`, or relative to the playlist. */
    NORMALISED,

    /** Same trailing path under a different mount point — `/mnt/sdcard/…` vs `/storage/emulated/0/…`. */
    SUFFIX,

    /** Only the filename could be matched. */
    FILENAME,

    /** No path matched; the `#EXTINF` artist and title named exactly one library row. */
    TAGS,

    /** Nothing matched, or every rung that could have matched was ambiguous. */
    UNRESOLVED,
}

/**
 * One playlist entry joined to the library row it names, plus the evidence that joined them.
 *
 * [song] is null exactly when [step] is [MatchStep.UNRESOLVED]. That is a first-class outcome, not
 * an error: the UI renders the entry greyed under its imported title, so the user sees a track they
 * can fix rather than a playlist that silently got shorter.
 *
 * [entry] is handed back exactly as parsed. The resolver reports; it does not edit. In particular
 * a [MatchStep.TAGS] match does **not** copy the `#EXTINF` metadata onto [song] — see
 * [LocalEntryResolver].
 */
data class Resolution(val entry: M3uEntry, val song: Song?, val step: MatchStep)

/**
 * Matches parsed `.m3u` entries against the indexed library.
 *
 * Pure and framework-free: no Android imports, no file I/O, no injection. It is given the entries
 * ([M3uParser]'s output, paths verbatim), the library, and the directory the playlist file itself
 * lives in, and it returns one [Resolution] per entry, in order. **`entries.size` always equals the
 * returned size** — an entry that matches nothing comes back as `song = null`.
 *
 * ## The two rules that matter more than the matching
 *
 * **1. An ambiguous rung does not match.** If a rung finds more than one candidate it falls through
 * to the next rung, and if every rung is ambiguous the entry is [MatchStep.UNRESOLVED]. Guessing
 * between two files with the same name is how a playlist quietly acquires the wrong track, and here
 * that is not cosmetic: `PlaylistRepository.resolve` writes the resolved `songId` back, so a wrong
 * match corrupts the cache permanently, on a *read* path, with no user action to associate it with.
 * Every normalisation below therefore has a safety net — the folds that *could* merge two real
 * files (case, `\`) produce a two-candidate bucket rather than a wrong answer.
 *
 * **2. `#EXTINF` is a hint, never a fact.** Playlists are routinely stale or hand-edited. The tags
 * rung uses the `#EXTINF` artist and title to *find* a candidate and never to describe it: the
 * [Song] returned is the library's own row, untouched. Where both claim to know the title, the
 * library wins.
 *
 * ## The key space is namespaced by construction
 *
 * All rungs share one flat `Map<String, List<Song>>`, so every key carries an explicit rung prefix
 * (`r1:`…`r5:`). Before this class the rungs of `LocalSource.stableKey` merely *happened* not to
 * overlap — a relative key never starts with `/`, a `DATA` path always does, a uri always starts
 * `content://` — which is luck, not design (see `LocalSource.composeRelativeKey`'s note for Task 4).
 * A tags rung has no such discipline: `4:beck:lost cause` is a legal filename on ext4. The prefixes
 * are what make the space unambiguous; `LocalEntryResolverTest`'s pairwise-distinctness table is
 * what keeps them that way when a sixth rung is added.
 *
 * ## Every normalisation, and what it must NOT collapse
 *
 * | Fold | Why | Must not collapse | Guard |
 * |---|---|---|---|
 * | `\` → `/` | Windows-authored playlists write `Music\Beck\Lost.mp3` | `a\b.mp3`, a legal POSIX filename, with the path `a/b.mp3` | [MatchStep.EXACT] runs first and is byte-exact; and a two-candidate bucket cannot match |
 * | runs of `/` → one | `a//b.mp3` and `a/b.mp3` are the same file on every filesystem Android runs on — an empty directory name does not exist | nothing real | — |
 * | drop `.`, pop `..` | playlists next to their music write `../Music/x.mp3` | a segment of `...`, or any other name that merely starts with a dot — the check is equality, not `startsWith` | equality |
 * | `lowercase()` (rungs 2–5) | FAT/exFAT SD cards are case-insensitive and playlist writers case-mangle freely | `A.mp3` and `a.mp3`, which coexist on ext4 | rung 1 is byte-exact; and both land in one bucket, which is ambiguous and so does not match |
 * | trim + lowercase on tags | `#EXTINF:210, Beck - Lost Cause` is ordinary, and no artist is distinguished by its padding | two different tag sets — the key is length-prefixed, not delimited, because any delimiter can occur inside a title | length prefix |
 * | *no* whitespace trim on paths | `" a.mp3"` and `"a.mp3"` are two files in one directory on ext4/f2fs, and `" Music"` and `"Music"` two directories | — | not performed at all |
 *
 * The last row is the load-bearing non-normalisation, and it is the same rule `composeRelativeKey`
 * settled on after two rounds: only separators are trimmed from a path, never whitespace.
 */
object LocalEntryResolver {

    /**
     * Rung namespaces. Not decoration: they are the only thing keeping five different kinds of
     * string apart in one map. See the class KDoc.
     */
    private const val P_EXACT = "r1:"
    private const val P_NORMALISED = "r2:"
    private const val P_SUFFIX = "r3:"
    private const val P_FILENAME = "r4:"
    private const val P_TAGS = "r5:"

    /**
     * The shortest trailing path the [MatchStep.SUFFIX] rung will consider. One segment is a bare
     * filename, which is the [MatchStep.FILENAME] rung's job and a materially weaker claim; keeping
     * them apart is what lets a test tell the two rungs apart.
     */
    private const val MIN_SUFFIX_SEGMENTS = 2

    /** Separates the volume from the volume-relative path in [Song.relativeKey]. */
    private const val VOLUME_SEPARATOR = ':'

    /**
     * A path reduced to its meaningful parts: whether it was rooted, and its segments with
     * separator noise and `.`/`..` resolved away. Segment *contents* are untouched — whitespace and
     * case included.
     */
    private data class Canonical(val rooted: Boolean, val segments: List<String>)

    // ------------------------------------------------------------------ the public entry point

    /**
     * Resolve [entries] against [library], treating relative paths as relative to [playlistDir]
     * (the directory containing the playlist file; null when it is unknown, e.g. a SAF document
     * whose parent could not be determined).
     *
     * The lookup index is built once for the whole call, not once per entry.
     */
    fun resolve(
        entries: List<M3uEntry>,
        library: List<Song>,
        playlistDir: String?,
    ): List<Resolution> {
        if (entries.isEmpty()) return emptyList()
        val index = buildIndex(library)
        return entries.map { resolveOne(it, index, playlistDir) }
    }

    private fun resolveOne(
        entry: M3uEntry,
        index: Map<String, List<Song>>,
        playlistDir: String?,
    ): Resolution {
        // Rung 1 — byte for byte, against the uri and the DATA path. No folding whatsoever, which
        // is what makes it safe for the folds below to be as broad as they are.
        index.unique(P_EXACT + entry.path)?.let { return Resolution(entry, it, MatchStep.EXACT) }

        val canonical = canonicalise(entry.path, playlistDir)

        // Rung 2 — the same path written differently, and the only rung that uses playlistDir.
        index.unique(normalisedKeyOf(canonical))
            ?.let { return Resolution(entry, it, MatchStep.NORMALISED) }

        // Rung 3 — the same trailing path under a different root.
        suffixMatch(index, canonical.segments)
            ?.let { return Resolution(entry, it, MatchStep.SUFFIX) }

        // Rung 4 — the filename alone.
        filenameOf(canonical.segments)?.let { name ->
            index.unique(P_FILENAME + name.lowercase())
                ?.let { return Resolution(entry, it, MatchStep.FILENAME) }
        }

        // Rung 5 — no path matched; fall back to what the playlist claims the track IS.
        tagsKey(entry.artist, entry.title)?.let { key ->
            index.unique(key)?.let { return Resolution(entry, it, MatchStep.TAGS) }
        }

        return Resolution(entry, null, MatchStep.UNRESOLVED)
    }

    /**
     * The candidate under [key], or null when there is none **or more than one**.
     *
     * `singleOrNull` is the whole ambiguity rule in one call: a bucket holding two songs is not a
     * near-miss to be broken by a tie-break, it is a question this class is not entitled to answer.
     */
    private fun Map<String, List<Song>>.unique(key: String): Song? = this[key]?.singleOrNull()

    /**
     * Walk the entry's trailing segments from longest to shortest and take the first tail any song
     * is indexed under.
     *
     * Longest-first is the point: `/mnt/sd/Music/Live/Lost.mp3` and `/mnt/sd/Archive/Live/Lost.mp3`
     * are indistinguishable on two segments and distinct on three, so a shortest-first walk would
     * call both ambiguous and resolve neither.
     *
     * An ambiguous tail ends the walk rather than shortening further. Candidate sets only grow as
     * the tail shortens — a song matching a k-segment tail matches the (k-1)-segment one too — so a
     * shorter tail can never be more decisive, and continuing would only burn time.
     */
    private fun suffixMatch(index: Map<String, List<Song>>, segments: List<String>): Song? {
        for (k in segments.size downTo MIN_SUFFIX_SEGMENTS) {
            val bucket = index[suffixKeyOf(segments.takeLast(k))] ?: continue
            return bucket.singleOrNull()
        }
        return null
    }

    // ------------------------------------------------------------------ the index

    /**
     * Index every song under every key it could be found by.
     *
     * A song appears under a given key **at most once**, by object identity. Both [Song.filePath]
     * and [Song.relativeKey] usually describe the same file and therefore produce identical
     * filename and suffix keys; counting that as two candidates would make every fully-populated
     * row look ambiguous, and the ambiguity rule — the safety property — would become the bug.
     */
    private fun buildIndex(library: List<Song>): Map<String, List<Song>> {
        val index = LinkedHashMap<String, MutableList<Song>>()

        fun put(key: String, song: Song) {
            val bucket = index.getOrPut(key) { ArrayList(1) }
            if (bucket.none { it === song }) bucket += song
        }

        for (song in library) {
            put(P_EXACT + song.uri, song)
            song.filePath?.takeIf { it.isNotEmpty() }?.let { put(P_EXACT + it, song) }

            for (location in locationsOf(song)) {
                put(normalisedKeyOf(location), song)
                for (k in MIN_SUFFIX_SEGMENTS..location.segments.size) {
                    put(suffixKeyOf(location.segments.takeLast(k)), song)
                }
                filenameOf(location.segments)?.let { put(P_FILENAME + it.lowercase(), song) }
            }

            tagsKey(song.artist, song.title)?.let { put(it, song) }
        }
        return index
    }

    /**
     * The path-shaped identities of a song: its `DATA` path, and the volume-relative path inside
     * its [Song.relativeKey].
     *
     * Both, not one. `DATA` is nullable from API 29 — the assumption that cost Task 2 a round — and
     * a row known only by its `VOLUME:RELATIVE_PATH/DISPLAY_NAME` triple still has to resolve. The
     * volume is stripped because no playlist ever writes `external_primary:`; the remainder is a
     * genuine relative path, so it stays unrooted and matches on the relative and suffix rungs
     * rather than pretending to be absolute.
     */
    private fun locationsOf(song: Song): List<Canonical> {
        val out = ArrayList<Canonical>(2)
        song.filePath?.takeIf { it.isNotEmpty() }?.let { out += canonicalise(it, null) }
        song.relativeKey?.let { key ->
            val separator = key.indexOf(VOLUME_SEPARATOR)
            if (separator >= 0) {
                val relative = key.substring(separator + 1)
                if (relative.isNotEmpty()) out += canonicalise(relative, null)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ normalisation

    /**
     * Split [rawPath] into segments, resolving it against [playlistDir] when it is relative.
     *
     * Nothing inside a segment is touched: no trimming, no case folding. `" Music"` and `"Music"`
     * are two real directories on ext4/f2fs and `" a.mp3"` and `"a.mp3"` two real files, which is
     * the exact collapse `composeRelativeKey` lost two rounds to. Case folding happens later, in
     * the key functions, so that the one rung that must stay byte-exact can share this code.
     */
    private fun canonicalise(rawPath: String, playlistDir: String?): Canonical {
        val text = rawPath.replace('\\', '/')
        val joined = when {
            text.startsWith("/") || playlistDir.isNullOrEmpty() -> text
            else -> playlistDir.replace('\\', '/') + "/" + text
        }
        val rooted = joined.startsWith("/")

        val segments = ArrayList<String>()
        for (segment in joined.split('/')) {
            when {
                // A run of separators, or a trailing one. No filesystem has an empty directory name.
                segment.isEmpty() -> Unit
                // Equality, not startsWith: "..." is an ordinary legal name and must survive.
                segment == "." -> Unit
                segment == ".." -> when {
                    segments.isNotEmpty() -> segments.removeAt(segments.lastIndex)
                    // `/..` is `/` on POSIX, so an absolute path may swallow it.
                    rooted -> Unit
                    // A relative path with nothing to climb out of is genuinely unresolved. Keeping
                    // the segment stops "../Music/a.mp3" from silently becoming "Music/a.mp3" and
                    // matching a file it does not name; the suffix and filename rungs still get
                    // their chance, since the ".." only ever sits at the front.
                    else -> segments += segment
                }
                else -> segments += segment
            }
        }
        return Canonical(rooted = rooted, segments = segments)
    }

    /** The last segment, or null when there is none or it is a parent reference rather than a name. */
    private fun filenameOf(segments: List<String>): String? =
        segments.lastOrNull()?.takeUnless { it == ".." }

    private fun normalisedKeyOf(canonical: Canonical): String =
        P_NORMALISED + (if (canonical.rooted) "/" else "") +
            canonical.segments.joinToString("/").lowercase()

    private fun suffixKeyOf(tail: List<String>): String =
        P_SUFFIX + tail.joinToString("/").lowercase()

    // ------------------------------------------------------------------ keys, for the tests
    //
    // These exist so the pairwise-distinctness table can address every rung's key space directly.
    // `lowercase()` throughout is the no-argument overload, which folds against the root locale —
    // the locale-sensitive overload turns a Turkish "I" into "ı" and would make a library resolve
    // differently depending on the user's phone language.

    /** Rung 1: the raw playlist line, byte for byte. */
    internal fun exactKey(rawPath: String): String = P_EXACT + rawPath

    /** Rung 2: the whole path, folded, resolved against [playlistDir] when relative. */
    internal fun normalisedKey(rawPath: String, playlistDir: String? = null): String =
        normalisedKeyOf(canonicalise(rawPath, playlistDir))

    /** Rung 3: the last [segmentCount] segments, folded. Null if the path has fewer, or fewer than [MIN_SUFFIX_SEGMENTS]. */
    internal fun suffixKey(rawPath: String, segmentCount: Int): String? {
        val segments = canonicalise(rawPath, null).segments
        if (segmentCount < MIN_SUFFIX_SEGMENTS || segmentCount > segments.size) return null
        return suffixKeyOf(segments.takeLast(segmentCount))
    }

    /** Rung 4: the filename alone, folded. */
    internal fun filenameKey(rawPath: String): String? =
        filenameOf(canonicalise(rawPath, null).segments)?.let { P_FILENAME + it.lowercase() }

    /**
     * Rung 5: artist and title, trimmed and folded, **length-prefixed rather than delimited**.
     *
     * Any delimiter one might pick can occur inside a real title, and a delimited key would merge
     * `("a|b", "c")` with `("a", "b|c")` — two different tracks under one key, which under the
     * ambiguity rule is not merely inelegant: it costs both of them their match. The length prefix
     * makes the encoding injective by construction rather than by hoping about `|`.
     *
     * Null unless BOTH parts are present. Half a tag set is not a tag set: matching on title alone
     * would let a playlist entry claim any cover version, and matching on the empty strings that
     * MediaStore leaves on an untagged file would pile the whole untagged half of a library into
     * one bucket.
     */
    internal fun tagsKey(artist: String?, title: String?): String? {
        val a = artist?.trim()?.lowercase().orEmpty()
        val t = title?.trim()?.lowercase().orEmpty()
        if (a.isEmpty() || t.isEmpty()) return null
        return "$P_TAGS${a.length}:$a:$t"
    }
}
