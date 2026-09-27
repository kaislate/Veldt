// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding 19, driven through the real composables: the search field's clear, and what a trip to
 * search does with the text the last trip left behind.
 *
 * The screen's own `SearchScreen` is not composed here; it needs two view models over Room and a
 * playback connection, none of which touch these behaviours. [SearchBar] composes the two pieces
 * that do, [StartSearchTrip] then [SearchField], in the order the screen calls them, over a
 * [SearchQuery] standing in for the one `BrowseViewModel` owns. Holding the query outside the
 * composition is the point: it is what the activity-scoped view model does, and it is why the
 * last trip's text is still there to be cleared.
 *
 * Every assertion reads BOTH the field's semantics (what is on screen) and the [SearchQuery]
 * (what the pipeline will search for), because finding 19 was precisely those two disagreeing.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pinned as the other Robolectric suites are.
@Config(sdk = [34])
class SearchFieldTest {

    @get:Rule val compose = createComposeRule()

    @Composable
    private fun SearchBar(search: SearchQuery) {
        val focus = remember { FocusRequester() }
        StartSearchTrip(search = search, focusRequester = focus)
        SearchField(search = search, onBack = {}, onSubmit = {}, focusRequester = focus)
    }

    /** The text the field is showing, from its semantics: the editable text, not the placeholder. */
    private fun shown(): String =
        compose.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    private fun field() = compose.onNode(hasSetTextAction())

    // ---- the X ----------------------------------------------------------------------

    @Test fun `the X empties the field, and the next keystroke is all that is there`() {
        val search = SearchQuery()
        compose.setContent { SearchBar(search) }

        field().performTextInput("abc")
        assertEquals("abc", shown())

        compose.onNodeWithContentDescription("Clear search").performClick()
        assertEquals("", shown())
        assertEquals("", search.text.value)

        field().performTextInput("d")
        assertEquals("d", shown())
        assertEquals("d", search.text.value)
    }

    /**
     * The shape the owner hit: a keyboard mid-way through composing a word when the X is tapped.
     * The composing region is set directly, as an IME's setComposingText would leave it; the test
     * input API only commits text, so it cannot open a composition itself.
     */
    @Test fun `the X ends an open IME composition along with the text`() {
        val search = SearchQuery()
        compose.setContent { SearchBar(search) }
        compose.runOnIdle {
            search.edit(
                TextFieldValue("Granite", selection = TextRange(7), composition = TextRange(0, 7)),
            )
        }
        assertEquals("Granite", shown())

        compose.onNodeWithContentDescription("Clear search").performClick()

        assertEquals("", shown())
        assertEquals(TextFieldValue(), search.field)
        assertNull("a clear must not leave a composing region behind", search.field.composition)

        field().performTextInput("g6")
        assertEquals("g6", shown())
        assertEquals("g6", search.text.value)
    }

    // ---- a trip to search -----------------------------------------------------------

    @Test fun `a fresh trip opens on an empty field, whatever the last trip left`() {
        val search = SearchQuery().apply { edit(TextFieldValue("abba")) }

        compose.setContent { SearchBar(search) }

        assertEquals("", shown())
        assertEquals("", search.field.text)
        assertEquals("the pipeline must be told too, or it searches the old term", "", search.text.value)
    }

    /**
     * The back-stack return: the screen is disposed when an album opens from a result and
     * recomposed from its saved state when the user comes back. Emulated here with the saved-state
     * round trip itself, which is what the nav back stack entry supplies.
     */
    @Test fun `a return from a result keeps the query`() {
        val search = SearchQuery()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { SearchBar(search) }
        field().performTextInput("abba")
        assertEquals("abba", shown())

        restoration.emulateSavedInstanceStateRestore()

        assertEquals("abba", shown())
        assertEquals("abba", search.text.value)
    }
}
