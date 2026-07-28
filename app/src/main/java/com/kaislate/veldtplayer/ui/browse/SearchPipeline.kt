// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The search state machine, as flow operators over a field's text — free of the ViewModel,
 * of Room and of Android, so the part with the timing in it is the part that is testable.
 *
 * There are TWO windows between a keystroke and a verdict, and both have to close before
 * "no matches" is an honest thing to say:
 *
 *  1. the **debounce** window — the field is still being typed in ([settleSearchTerms]);
 *  2. the **query** window — the settled term has been handed to Room, which answers on its
 *     own executor some milliseconds later ([answerSearch]).
 *
 * Closing only the first is the trap: the instant a term settles, everything derived purely
 * from it (the album and artist shelves) updates in the same dispatch, while the song rows
 * still hold the PREVIOUS term's answer. A screen that reads "settled, and nothing here"
 * as a verdict will therefore announce "No matches for X" about a query still in flight,
 * and then pop the songs in underneath the message. Tagging the rows with the term they
 * answer ([SearchAnswer]) is what makes "answered empty" distinguishable from
 * "not yet answered" — see [searchPending].
 */

/**
 * How long the field rests before its text becomes a query. Short enough that results feel
 * live, long enough that a fast typist costs one Room query instead of one per keystroke.
 */
internal const val SEARCH_DEBOUNCE_MS = 180L

/**
 * Rows, plus the term they are the answer TO.
 *
 * The term is the whole point: a bare `List<Song>` cannot tell a caller whether an empty
 * list means "this term matched nothing" or "nobody has asked this question yet".
 */
internal data class SearchAnswer(val term: String, val songs: List<Song>) {
    companion object {
        /** Nothing asked, nothing answered — the seed for the state before the first term. */
        val NONE = SearchAnswer(term = "", songs = emptyList())
    }
}

/**
 * Field text → the terms actually worth running: trimmed, and only once typing has paused
 * for [debounceMs].
 *
 * `distinctUntilChanged` sits AFTER the trim so that adding or removing trailing whitespace
 * is not a new question, and so a term that settles to what is already running does not
 * cancel and restart the query behind it.
 */
@OptIn(FlowPreview::class)
internal fun Flow<String>.settleSearchTerms(debounceMs: Long = SEARCH_DEBOUNCE_MS): Flow<String> =
    debounce(debounceMs)
        .map { it.trim() }
        .distinctUntilChanged()

/**
 * Settled terms → their rows, each tagged with the term that produced them.
 *
 * A blank term is answered immediately and locally with nothing: [search] is never called
 * for it, because a search screen that dumps the whole library before you type reads as
 * broken. `flatMapLatest` drops the in-flight query when a newer term arrives, so a fast
 * retype cannot land an older answer on top of a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<String>.answerSearch(
    search: (String) -> Flow<List<Song>>,
): Flow<SearchAnswer> = flatMapLatest { term ->
    if (term.isBlank()) {
        flowOf(SearchAnswer(term, emptyList()))
    } else {
        search(term).map { rows -> SearchAnswer(term, rows) }
    }
}

/**
 * Is the screen still waiting on an answer for the text it is showing?
 *
 * True while EITHER window is open: [query] has moved on from [settled] (still typing), or
 * [settled] has moved on from [answeredTerm] (query in flight). Only when both are shut do
 * empty results mean "nothing matched" — every other moment they mean "not yet".
 */
internal fun searchPending(query: String, settled: String, answeredTerm: String): Boolean =
    query.trim() != settled || settled != answeredTerm
