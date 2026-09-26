// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Plain JVM — [LyricsResolver] touches no Android type; [LyricsProvider] is a fun interface, so
 * every chain link below is a plain lambda recording its own call.
 *
 * Every "real" (non-weak) [Lyrics.Plain] fixture below is deliberately TWO lines
 * (`"$name line one\n$name line two"`) rather than one — a single-line `Plain` is WEAK per spec
 * §5's "Weak results" (see the class KDoc on [LyricsResolver]), and a fixture that was meant to be
 * a plain, uncomplicated hit must not accidentally exercise the weak/fallback path instead. The
 * dedicated weak-result tests further down use single-line text on purpose.
 *
 * Negative control this file is designed to catch (see the task report): swapping the local
 * chain's provider order (`embedded` before `sidecar`) reddens `a local track tries sidecar,
 * then embedded, then lrclib` by changing the recorded call sequence.
 */
class LyricsResolverTest {

    private val localSourceId = "local-src"

    private fun song(sourceId: String, externalId: String) = Song(
        id = 1L,
        sourceId = sourceId,
        externalId = externalId,
        uri = "content://media/1",
        filePath = null,
        relativeKey = null,
        title = "t",
        artist = "a",
        album = "al",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 0L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    /** Records its own name (in call order, shared across every provider passed the same list)
     *  and answers [result]. */
    private fun provider(name: String, calls: MutableList<String>, result: Lyrics?): LyricsProvider =
        LyricsProvider { calls += name; result }

    /** A real (non-weak) two-line hit attributed to [name] — see the class KDoc. */
    private fun real(name: String): Lyrics.Plain = Lyrics.Plain("$name line one\n$name line two")

    /** A weak (single-line) hit — the shape of the owner's watermark measurement. */
    private fun weak(name: String): Lyrics.Plain = Lyrics.Plain("$name watermark")

    private fun resolver(
        calls: MutableList<String>,
        sidecarResult: Lyrics? = null,
        embeddedResult: Lyrics? = null,
        serverResult: Lyrics? = null,
        lrclibResult: Lyrics? = null,
    ) = LyricsResolver(
        localSourceId = localSourceId,
        sidecar = provider("sidecar", calls, sidecarResult),
        embedded = provider("embedded", calls, embeddedResult),
        server = provider("server", calls, serverResult),
        lrclib = provider("lrclib", calls, lrclibResult),
    )

    // ------------------------------------------------------- local chain: sidecar -> embedded -> lrclib

    @Test fun `a local track with a sidecar hit asks only sidecar`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = real("sidecar"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(ResolvedLyrics(real("sidecar"), LyricsSource.SIDECAR), result)
        assertEquals(listOf("sidecar"), calls)
    }

    @Test fun `a local track with no sidecar but an embedded hit asks sidecar then embedded`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, embeddedResult = real("embedded"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(ResolvedLyrics(real("embedded"), LyricsSource.EMBEDDED), result)
        assertEquals(listOf("sidecar", "embedded"), calls)
    }

    @Test fun `a local track that falls through to lrclib asks sidecar, embedded, then lrclib`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, lrclibResult = real("lrclib"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(ResolvedLyrics(real("lrclib"), LyricsSource.LRCLIB), result)
        assertEquals(listOf("sidecar", "embedded", "lrclib"), calls)
    }

    @Test fun `a local track with nothing anywhere is null, having tried every local provider`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls)
        val result = resolver.resolve(song(localSourceId, "1"))
        assertNull(result)
        assertEquals(listOf("sidecar", "embedded", "lrclib"), calls)
    }

    @Test fun `a local track never asks server`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, serverResult = real("should never be reached"))
        resolver.resolve(song(localSourceId, "1"))
        assertEquals(false, "server" in calls)
    }

    // -------------------------------------------------------------- remote chain: server -> lrclib

    @Test fun `a remote track with a server hit asks only server`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, serverResult = real("server"))
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(ResolvedLyrics(real("server"), LyricsSource.SERVER), result)
        assertEquals(listOf("server"), calls)
    }

    @Test fun `a remote track that falls through to lrclib asks server then lrclib`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, lrclibResult = real("lrclib"))
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(ResolvedLyrics(real("lrclib"), LyricsSource.LRCLIB), result)
        assertEquals(listOf("server", "lrclib"), calls)
    }

    @Test fun `a remote track with nothing anywhere is null, having tried both remote providers`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls)
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertNull(result)
        assertEquals(listOf("server", "lrclib"), calls)
    }

    @Test fun `a remote track never asks sidecar or embedded`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(
            calls,
            sidecarResult = real("should never be reached"),
            embeddedResult = real("should never be reached"),
        )
        resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(emptyList<String>(), calls.filter { it == "sidecar" || it == "embedded" })
    }

    // ------------------------------------------------------------------------- weak results (§5)

    @Test fun `a weak sidecar result falls back while a real embedded result wins`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = weak("sidecar"), embeddedResult = real("embedded"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(
            "a real embedded hit must win over a weak sidecar one, and lrclib must not be asked",
            ResolvedLyrics(real("embedded"), LyricsSource.EMBEDDED),
            result,
        )
        assertEquals(listOf("sidecar", "embedded"), calls)
    }

    @Test fun `a weak sidecar result with nothing else anywhere is returned weak`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = weak("sidecar"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(
            "the sole weak result must still be returned once the whole chain is exhausted",
            ResolvedLyrics(weak("sidecar"), LyricsSource.SIDECAR),
            result,
        )
        assertEquals(listOf("sidecar", "embedded", "lrclib"), calls)
    }

    @Test fun `a weak server result falls back while a real lrclib result wins`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, serverResult = weak("server"), lrclibResult = real("lrclib"))
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(ResolvedLyrics(real("lrclib"), LyricsSource.LRCLIB), result)
        assertEquals(listOf("server", "lrclib"), calls)
    }

    @Test fun `weak server and weak lrclib both return — the FIRST weak one, server, wins`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, serverResult = weak("server"), lrclibResult = weak("lrclib"))
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(
            "two weak results must keep the FIRST one's source (server), not the last (lrclib)",
            ResolvedLyrics(weak("server"), LyricsSource.SERVER),
            result,
        )
        assertEquals(listOf("server", "lrclib"), calls)
    }

    @Test fun `a real multi-line server result wins outright — lrclib is never asked`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, serverResult = real("server"), lrclibResult = real("lrclib"))
        val result = resolver.resolve(song("navidrome-1", "s1"))
        assertEquals(ResolvedLyrics(real("server"), LyricsSource.SERVER), result)
        assertEquals("lrclib must never be reached once a real result has already won", listOf("server"), calls)
    }

    @Test fun `a single-line Synced result is not weak — it wins immediately`() = runTest {
        val calls = mutableListOf<String>()
        val oneLineSynced = Lyrics.Synced(listOf(LyricLine(0, "just one timed line")))
        val resolver = resolver(calls, sidecarResult = oneLineSynced, embeddedResult = real("embedded"))
        val result = resolver.resolve(song(localSourceId, "1"))
        assertEquals(
            "a single-line Synced result must never be treated as weak",
            ResolvedLyrics(oneLineSynced, LyricsSource.SIDECAR),
            result,
        )
        assertEquals(listOf("sidecar"), calls)
    }

    // ------------------------------------------------------------------------------------- memo

    @Test fun `a hit is memoised — the second resolve calls no provider`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = Lyrics.Plain("x"))
        val song = song(localSourceId, "1")

        val first = resolver.resolve(song)
        val callsAfterFirst = calls.toList()
        val second = resolver.resolve(song)

        assertEquals(first, second)
        assertEquals("the second resolve reached a provider instead of the memo", callsAfterFirst, calls)
    }

    @Test fun `a miss is memoised — the second resolve calls no provider`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls)
        val song = song(localSourceId, "1")

        assertNull(resolver.resolve(song))
        val callsAfterFirst = calls.toList()
        assertNull(resolver.resolve(song))

        assertEquals("a memoised MISS still reached a provider on the second call", callsAfterFirst, calls)
    }

    @Test fun `clear empties the memo, so the next resolve reaches the providers again`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = real("x"))
        val song = song(localSourceId, "1")

        resolver.resolve(song)
        resolver.clear()
        resolver.resolve(song)

        assertEquals(listOf("sidecar", "sidecar"), calls)
    }

    @Test fun `different tracks are memoised independently, keyed on sourceId and externalId together`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = Lyrics.Plain("x"))

        resolver.resolve(song(localSourceId, "1"))
        resolver.resolve(song(localSourceId, "2"))
        val afterTwoDistinctTracks = calls.size
        resolver.resolve(song(localSourceId, "1"))
        resolver.resolve(song(localSourceId, "2"))

        assertEquals(
            "re-resolving the same two tracks reached a provider instead of the memo",
            afterTwoDistinctTracks,
            calls.size,
        )
    }

    /** The bounded LRU: the 65th distinct track evicts the LEAST recently used of the first 64
     *  (track "0", never touched again), so re-resolving it reaches the provider a second time —
     *  while a track that WAS touched again survives. */
    @Test fun `the memo evicts its least-recently-used entry past 64 tracks`() = runTest {
        val calls = mutableListOf<String>()
        val resolver = resolver(calls, sidecarResult = real("x"))

        repeat(64) { i -> resolver.resolve(song(localSourceId, i.toString())) }
        val callsAfterFilling = calls.size

        // Touch track "1" again — an ACCESS, so accessOrder=true keeps it off the chopping block —
        // then add one more distinct track to force an eviction.
        resolver.resolve(song(localSourceId, "1"))
        resolver.resolve(song(localSourceId, "64"))
        val callsAfterOneMore = calls.size

        // Track "1" was touched most recently among the first 64, so it must still be memoised.
        resolver.resolve(song(localSourceId, "1"))
        assertEquals(
            "track \"1\" was evicted even though it was the most recently accessed",
            callsAfterOneMore,
            calls.size,
        )

        // Track "0" was the true least-recently-used and must have been evicted.
        resolver.resolve(song(localSourceId, "0"))
        assertEquals(
            "track \"0\" (the actual least-recently-used entry) was not evicted",
            calls.size - 1,
            callsAfterOneMore,
        )
    }
}
