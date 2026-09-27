// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The search field's text: what the field draws and what the debounce pipeline searches for.
 *
 * **Snapshot state, not a flow, because a text field must never be one frame behind.** This used
 * to be a `MutableStateFlow` the screen read back with `collectAsStateWithLifecycle`, so every
 * edit took a round trip — write the flow, dispatch the collector, recompose — before the field
 * saw its own text. An IME edit landing inside that window was applied to the STALE text and
 * written back over the newer value. Tapping the X and typing straight away was exactly that
 * race: the "" was overwritten by the old query plus the new keystrokes, which is how finding 19's
 * "Graniteg6 g6 g6" was produced. Here, [field] is the value the field is composed with, and an
 * [edit] is visible to the very next read, so the field and its state cannot disagree.
 *
 * **A [TextFieldValue], not a String, so a clear also ends the IME's composition.** A keyboard
 * that composes whole words holds its composing region in its own buffer. The String-level field
 * keeps a private TextFieldValue and, by design, preserves its composition across a text change
 * made from outside, so a String "" says nothing about the word the keyboard is still composing.
 * The value-level API lets [clear] say it outright: the new value has no composition at all, and
 * a change of text and composition together is what makes the field restart input.
 *
 * [text] is a one-way feed OUT to `settleSearchTerms`, written by the same call that writes
 * [field], and never read back by the field. So the flow's asynchrony stays in the pipeline, where
 * the debounce already expects it, and out of the field.
 */
@Stable
class SearchQuery {

    /** What the field shows: text, cursor and IME composition. Changed only by [edit] and [clear]. */
    var field: TextFieldValue by mutableStateOf(TextFieldValue())
        private set

    private val _text = MutableStateFlow("")

    /** The field's text, for the debounce pipeline. See the class KDoc for why it is output only. */
    val text: StateFlow<String> = _text.asStateFlow()

    /** Every change the field itself reports: keystrokes, IME commits, cursor moves. */
    fun edit(value: TextFieldValue) {
        field = value
        _text.value = value.text
    }

    /**
     * Empties the field: the X, and the start of a fresh trip to search. The default
     * [TextFieldValue] has no composition, which is the point; see the class KDoc.
     */
    fun clear() = edit(TextFieldValue())
}
