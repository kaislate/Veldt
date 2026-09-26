// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.LibrarySource
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.di.LocalLibrary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses which [LyricsProvider]s to ask for a [Song], in which order, and remembers the answer
 * per track for the life of the process (spec §5).
 *
 * **Chain** (first non-null, non-weak result wins): a LOCAL track (`song.sourceId ==
 * localSourceId`) tries [sidecar] → [embedded] → [lrclib]; anything else tries [server] →
 * [lrclib]. Server-only tracks skip sidecar/embedded outright — those two read [Song.filePath],
 * which is null for them anyway — and a local track never asks [server], which has nothing to
 * answer for a file this app never uploaded anywhere.
 *
 * **Weak results (spec §5, owner decision 2026-09-25):** a [Lyrics.Plain] whose text is a single
 * non-blank line is WEAK rather than accepted outright — see [isWeak]. A weak result is kept as a
 * fallback and the chain keeps going; the first NON-weak result still wins outright, and if the
 * whole chain ends with nothing but weak (or null) answers, the FIRST weak one wins. This exists
 * because all 26 of the owner's Navidrome songs with server lyrics are a single watermark line
 * (`www.t.me/pmedia_music`) pulled from a file tag — real lyrics, under first-non-null-wins,
 * would otherwise never be asked of LRCLIB at all.
 *
 * **Memo**: an in-process LRU of the last [MEMO_CAPACITY] `(sourceId, externalId)` pairs, keyed
 * exactly like [LrclibCache] (`sourceId + '\u0000' + externalId`) for the same reason — it is the
 * track's cross-source identity, never anything derived from a local path. Both a non-weak HIT
 * ([ResolvedLyrics]) and a MISS (null — nothing anywhere had lyrics) are memoised — a WEAK final
 * answer is not, see [resolve] — so a track with
 * genuinely no lyrics is not re-walked on every re-open; [clear] drops the whole memo, which the
 * view model calls when the LRCLIB opt-in flips (a miss recorded while it was off must not keep
 * hiding a hit LRCLIB could now provide, and vice versa turning it off should not go on serving a
 * remembered LRCLIB hit forever — though in practice the provider itself already re-gates that).
 *
 * Deliberately **not** wrapped in its own `withContext(Dispatchers.IO)`: every provider that does
 * real I/O ([SidecarLrcProvider], [EmbeddedLyricsProvider], [ServerLyricsProvider] via
 * `SubsonicClient`, [LrclibProvider] via [LrclibClient]) already dispatches its own blocking work
 * there, and the memo lookup either side of the chain is a single bounded map operation. Staying
 * off a fixed dispatcher is what keeps [resolve] driven end-to-end by whatever dispatcher the
 * caller (or a test) chooses, rather than silently handing every call to a real thread pool no
 * test scheduler controls.
 */
@Singleton
class LyricsResolver(
    private val localSourceId: String,
    private val sidecar: LyricsProvider,
    private val embedded: LyricsProvider,
    private val server: LyricsProvider,
    private val lrclib: LyricsProvider,
) {
    /** The constructor Hilt uses — see the class KDoc for why the primary constructor above
     *  takes plain [LyricsProvider]s instead: a test builds the chain from fakes with no Hilt
     *  graph involved. */
    @Inject constructor(
        @LocalLibrary localLibrary: LibrarySource,
        sidecar: SidecarLrcProvider,
        embedded: EmbeddedLyricsProvider,
        server: ServerLyricsProvider,
        lrclib: LrclibProvider,
    ) : this(localLibrary.id, sidecar, embedded, server, lrclib)

    /** Wraps a resolved answer so a memoised MISS (`value == null`) is distinguishable from "not
     *  memoised at all" (no entry). */
    private class CacheEntry(val value: ResolvedLyrics?)

    /** `accessOrder = true` plus [removeEldestEntry] is the standard bounded-LRU idiom: every
     *  [get] and [put] counts as an access, so the entry evicted is always the least-recently
     *  used, not merely the oldest inserted. Guarded by [lock] — [resolve] may run concurrently
     *  for more than one track (e.g. the mini-player and a full-screen lyrics view). */
    private val lock = Any()
    private val memo = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>): Boolean =
            size > MEMO_CAPACITY
    }

    suspend fun resolve(song: Song): ResolvedLyrics? {
        val key = memoKey(song)
        synchronized(lock) { memo[key] }?.let { return it.value }

        val resolved = if (song.sourceId == localSourceId) {
            chain(song, sidecar to LyricsSource.SIDECAR, embedded to LyricsSource.EMBEDDED, lrclib to LyricsSource.LRCLIB)
        } else {
            chain(song, server to LyricsSource.SERVER, lrclib to LyricsSource.LRCLIB)
        }

        // A WEAK final answer is returned but never memoised (controller ruling, Task 5 review):
        // providers answer null for failure as well as for absence, so "weak server line, then
        // nothing" may only mean LRCLIB was unreachable this time — memoising it would pin the
        // watermark for the rest of the process. Re-walking costs little: LRCLIB's own on-disk
        // negative cache already stops a genuine miss from being requested again.
        if (resolved == null || !resolved.lyrics.isWeak()) {
            synchronized(lock) { memo[key] = CacheEntry(resolved) }
        }
        return resolved
    }

    /** Drops every memoised answer — see the class KDoc for when the view model calls this. */
    fun clear() {
        synchronized(lock) { memo.clear() }
    }

    /**
     * Walks [providers] in order. A WEAK result (see [isWeak]) is remembered as [weakFallback]
     * rather than accepted outright, and the chain keeps going; the first NON-weak result wins
     * immediately, short-circuiting the rest of the chain exactly as before. If every provider
     * that answered at all answered weak (or nothing), the FIRST weak answer is returned — never
     * a later one, so `server weak + lrclib weak` keeps SERVER's source, not LRCLIB's (spec §5,
     * "Weak results").
     */
    private suspend fun chain(song: Song, vararg providers: Pair<LyricsProvider, LyricsSource>): ResolvedLyrics? {
        var weakFallback: ResolvedLyrics? = null
        for ((provider, source) in providers) {
            val lyrics = provider.lyricsFor(song) ?: continue
            if (lyrics.isWeak()) {
                if (weakFallback == null) weakFallback = ResolvedLyrics(lyrics, source)
                continue
            }
            return ResolvedLyrics(lyrics, source)
        }
        return weakFallback
    }

    /**
     * A [Lyrics.Plain] whose text — once split into lines — has exactly ONE non-blank line is
     * WEAK: real lyrics almost never fit on one line, while a watermark tag dropped whole into a
     * `LYRICS`/`USLT` field always does (measured: all 26 of the owner's server-lyrics songs are
     * exactly this). [Lyrics.Synced] is never weak, regardless of how few lines it has — a
     * single-line but genuinely TIMED result is still real, structured data no provider would
     * fabricate. Purely structural, per the spec: no URL or content matching, so a real one-line
     * lyric (short chants, some hooks) is misjudged the same way a watermark is judged correctly
     * — the trade the owner's measurement decided.
     */
    private fun Lyrics.isWeak(): Boolean {
        if (this !is Lyrics.Plain) return false
        // `lines()`, not `split('\n')`: it also splits on a bare '\r' (old Mac line endings, which
        // tag editors still write), where `split('\n')` would see one long line.
        return text.lines().count { it.isNotBlank() } == 1
    }

    private fun memoKey(song: Song): String = song.sourceId + '\u0000' + song.externalId

    private companion object {
        const val MEMO_CAPACITY = 64
    }
}
