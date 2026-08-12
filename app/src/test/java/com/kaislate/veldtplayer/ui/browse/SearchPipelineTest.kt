// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search STATE MACHINE — the debounce window, the query window, and the verdict that is
 * only allowed once both are shut.
 *
 * `SearchFilterTest` covers the pure matching rule; this covers the timing, which is where
 * a search screen actually goes wrong. The failure these exist to keep out is a false
 * negative: "No matches for X" rendered about a term whose rows have not come back yet, the
 * message then being shoved aside by the results a few milliseconds later.
 *
 * Virtual time throughout (`runTest`), as in `PlaybackErrorBatchTest`, so a 180 ms debounce
 * and a simulated Room round trip cost nothing and never flake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchPipelineTest {

    /** How long the fake "Room" takes to answer. Any non-zero value reproduces the bug. */
    private val roomMs = 40L

    private fun song(title: String) = title.hashCode().toLong().let { id -> Song(
        id = id,
        sourceId = "test-source",
        externalId = "ms-${id + 9000}",
        uri = "content://media/external/audio/media/$id",
        filePath = null,
        relativeKey = null,
        title = title,
        artist = "The Beatles",
        album = "Help!",
        albumArtist = "The Beatles",
        trackNumber = 13,
        discNumber = 1,
        year = 1965,
        durationMs = 125_000,
        dateModifiedSec = 0,
        hasEmbeddedArt = false,
    ) }

    /**
     * The ViewModel's wiring, minus the ViewModel: a field, the settled terms, and the
     * tagged answers, sharing eagerly into the test scope exactly as `BrowseViewModel`
     * shares into `viewModelScope`.
     *
     * [queried] records every term that reached the "repository", and [seen] every answer
     * that reached a collector — the two things a caller of these operators is trusting.
     */
    private class Pipeline(
        scope: CoroutineScope,
        private val latencyMs: Long,
        private val rows: (String) -> List<Song>,
    ) {
        val query = MutableStateFlow("")
        val queried = mutableListOf<String>()
        val seen = mutableListOf<SearchAnswer>()

        val settled: StateFlow<String> =
            query.settleSearchTerms().stateIn(scope, SharingStarted.Eagerly, "")

        val answered: StateFlow<SearchAnswer> = settled
            .answerSearch { term ->
                queried += term
                // Room answers on its own executor, never in the caller's dispatch.
                flow {
                    delay(latencyMs)
                    emit(rows(term))
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, SearchAnswer.NONE)

        init {
            scope.launch { answered.collect { seen += it } }
        }

        /** What the screen computes to decide whether an empty list is a verdict. */
        fun pending(): Boolean = searchPending(query.value, settled.value, answered.value.term)
    }

    private fun TestScope.pipeline(
        latencyMs: Long = 40L,
        rows: (String) -> List<Song> = { emptyList() },
    ): Pipeline = Pipeline(backgroundScope, latencyMs, rows).also { runCurrent() }

    // ---- the debounce window --------------------------------------------------------

    @Test
    fun `a fast typist costs one query, not one per keystroke`() = runTest {
        val p = pipeline(latencyMs = roomMs)

        "abbey".forEachIndexed { i, _ ->
            p.query.value = "abbey".take(i + 1)
            advanceTimeBy(SEARCH_DEBOUNCE_MS / 3)
        }
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("abbey"), p.queried)
        assertEquals("abbey", p.settled.value)
    }

    @Test
    fun `the settled term is trimmed, and whitespace alone is not a new question`() = runTest {
        val p = pipeline(latencyMs = roomMs)

        p.query.value = "  abbey  "
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()
        assertEquals("abbey", p.settled.value)
        assertEquals(listOf("abbey"), p.queried)

        // Trailing space added: the same question, so no second Room round trip — and, as
        // far as the screen is concerned, nothing is pending.
        p.query.value = "abbey "
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()
        assertEquals(listOf("abbey"), p.queried)
        assertFalse(p.pending())
    }

    @Test
    fun `text still being typed is pending, so nothing is claimed about it`() = runTest {
        val p = pipeline(latencyMs = roomMs)

        p.query.value = "b"
        advanceTimeBy(SEARCH_DEBOUNCE_MS / 2)
        runCurrent()

        // Half a debounce in: the field says "b", nothing has run. "No matches for b" here
        // is the flash every fast typist used to get.
        assertEquals("", p.settled.value)
        assertTrue(p.pending())
        assertTrue(p.queried.isEmpty())
    }

    // ---- the query window (the regression this suite exists for) ---------------------

    /**
     * THE case. "yesterday" is a song TITLE on *Help!* by *The Beatles*, so neither the
     * album shelf nor the artist shelf names it — the song rows are the only thing that can
     * answer it, and they are the one part of the screen that cannot update in the same
     * dispatch as the term.
     *
     * Between the debounce closing and Room answering, `settled` says "yesterday" while the
     * rows still hold the previous term's list. A verdict drawn from `settled` alone
     * therefore reads "No matches for yesterday" and is then contradicted by the songs
     * popping in underneath it.
     */
    @Test
    fun `no verdict while the settled term's rows are still in flight`() = runTest {
        val p = pipeline(latencyMs = roomMs, rows = { term ->
            if (term == "yesterday") listOf(song("Yesterday")) else emptyList()
        })

        p.query.value = "yesterday"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        // Debounce shut: the term has settled and the pure shelves have already moved on.
        assertEquals("yesterday", p.settled.value)
        // Room has not. The rows still answer the term BEFORE it — here, the initial none.
        assertEquals("", p.answered.value.term)
        assertEquals(emptyList<Song>(), p.answered.value.songs)
        // So the screen must still hold its tongue.
        assertTrue("empty rows here mean 'not yet', not 'nothing'", p.pending())

        advanceTimeBy(roomMs)
        runCurrent()

        assertEquals("yesterday", p.answered.value.term)
        assertEquals(listOf("Yesterday"), p.answered.value.songs.map { it.title })
        assertFalse(p.pending())
    }

    /**
     * The same window on the second search of a session, where the stale list is a real
     * previous result rather than the initial empty one — the rows must never be shown
     * under the wrong term's heading.
     */
    @Test
    fun `rows never outlive the term they answer`() = runTest {
        val p = pipeline(latencyMs = roomMs, rows = { term ->
            if (term == "help") listOf(song("Help!")) else listOf(song("Yesterday"))
        })

        p.query.value = "help"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()
        assertEquals(SearchAnswer("help", listOf(song("Help!"))), p.answered.value)

        p.query.value = "yesterday"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        // Mid-flight the value still carries "help" — with ITS rows, not "yesterday" with
        // them. Every answer this collector ever saw is internally consistent.
        assertEquals("help", p.answered.value.term)
        assertTrue(p.pending())
        assertTrue(p.seen.all { it.songs == p.rowsForCheck(it.term) })
    }

    /** Mirrors the fake repository, so the consistency assertion above has something to check. */
    private fun Pipeline.rowsForCheck(term: String): List<Song> = when (term) {
        "" -> emptyList()
        "help" -> listOf(song("Help!"))
        else -> listOf(song("Yesterday"))
    }

    // ---- verdicts that ARE legitimate ------------------------------------------------

    @Test
    fun `a term that is answered with nothing is a real verdict`() = runTest {
        val p = pipeline(latencyMs = roomMs, rows = { emptyList() })

        p.query.value = "zzqq"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()

        assertEquals(SearchAnswer("zzqq", emptyList()), p.answered.value)
        assertFalse("both windows are shut — this one really did match nothing", p.pending())
    }

    /**
     * Two zero-result terms in a row is the shape that hides a broken `pending`: the rows
     * are empty before AND after, so only the TERM can say whether the answer is in.
     */
    @Test
    fun `a second term after a zero-result term still gets its own window`() = runTest {
        val p = pipeline(latencyMs = roomMs, rows = { term ->
            if (term == "yesterday") listOf(song("Yesterday")) else emptyList()
        })

        p.query.value = "zzqq"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()
        assertFalse(p.pending())

        p.query.value = "yesterday"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals("zzqq", p.answered.value.term)
        assertTrue("the empty list is still zzqq's answer, not yesterday's", p.pending())

        advanceTimeBy(roomMs)
        runCurrent()
        assertFalse(p.pending())
        assertEquals(listOf("Yesterday"), p.answered.value.songs.map { it.title })
    }

    // ---- blank, and cancellation ------------------------------------------------------

    @Test
    fun `a blank term is answered locally and never reaches the repository`() = runTest {
        val p = pipeline(latencyMs = roomMs, rows = { listOf(song("Yesterday")) })

        p.query.value = "yesterday"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + roomMs + 1)
        runCurrent()
        assertEquals(listOf("yesterday"), p.queried)

        // Cleared with the X. No query, no whole-library dump, and — with the field, the
        // settled term and the answer all blank — nothing pending either.
        p.query.value = ""
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("yesterday"), p.queried)
        assertEquals(SearchAnswer("", emptyList()), p.answered.value)
        assertFalse(p.pending())
    }

    /**
     * A slow query must not land on top of a newer one. With a Room round trip longer than
     * the debounce, retyping mid-flight means the older answer would otherwise arrive last
     * and overwrite the newer.
     */
    @Test
    fun `an in-flight query is dropped when a newer term settles`() = runTest {
        val slow = SEARCH_DEBOUNCE_MS * 4
        val p = pipeline(latencyMs = slow, rows = { term -> listOf(song(term)) })

        p.query.value = "hel"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        p.query.value = "help"
        advanceTimeBy(SEARCH_DEBOUNCE_MS + slow + 1)
        runCurrent()

        assertEquals(listOf("hel", "help"), p.queried)
        // "hel" was cancelled before it emitted, so no collector ever saw its answer.
        assertEquals(listOf("", "help"), p.seen.map { it.term })
        assertFalse(p.pending())
    }
}
