// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LocalSource.LIBRARY_SELECTION] must stay an ALLOWLIST.
 *
 * Owner decision 2026-08-13 widened the library to include audiobooks and podcasts. The obvious
 * implementation of "include more kinds of audio" is to relax or delete `IS_MUSIC != 0`, and that
 * is the bug: `MediaStore.Audio.Media` is not a music collection with some spoken word in it, it
 * is every audio file the device knows about — ringtones, alarms and notification sounds included.
 * Relaxing the filter files all of them under Songs, Artists and Albums.
 *
 * Not hypothetical. Queried on both test devices at the time of the change: each holds exactly one
 * `is_music=0` audio row, and on both it is a notification sound. A denylist-shaped fix (`AND
 * is_ringtone = 0 AND ...`) is the other tempting form and is worse, because it silently admits
 * every category Android adds later — `IS_RECORDING` arrived in API 31 exactly that way.
 *
 * These assertions are about the SHAPE of the predicate, not its text, so a reformat or a column
 * reordering does not fail them and a change of kind does.
 */
class LibrarySelectionTest {

    private val selection = LocalSource.LIBRARY_SELECTION

    /** Every kind we mean to include, named explicitly. */
    private val included = listOf("is_music", "is_podcast", "is_audiobook")

    /** Kinds that share the collection and must never be library content. */
    private val excluded = listOf("is_ringtone", "is_alarm", "is_notification", "is_recording")

    @Test fun `all three library kinds are admitted`() {
        val missing = included.filterNot { selection.contains(it) }
        assertTrue("LIBRARY_SELECTION does not admit: $missing — in: $selection", missing.isEmpty())
    }

    @Test fun `it is an allowlist — no excluded kind is even mentioned`() {
        // Mentioning one at all means the predicate has become a denylist, which is the failure
        // mode this guards: a denylist admits whatever category Android adds next.
        val mentioned = excluded.filter { selection.contains(it) }
        assertTrue("LIBRARY_SELECTION names excluded kinds $mentioned — that is a denylist: $selection", mentioned.isEmpty())
    }

    @Test fun `the kinds are ORed, so one match is enough`() {
        // ANDed, the predicate would demand a row be music AND a podcast AND an audiobook at once,
        // which nothing satisfies — the library would silently empty out.
        assertTrue("kinds must be ORed, not ANDed: $selection", selection.contains(" OR "))
        assertFalse("an AND between kinds admits nothing at all: $selection", selection.contains(" AND "))
    }

    /**
     * Parenthesised as a unit. Without this, appending `" AND $something"` at a call site binds to
     * the last OR branch only — `music OR podcast OR (audiobook AND x)` — which re-admits all
     * music and all podcasts regardless of the added clause. A trailing-clause bug that reads as
     * a tightening while actually being a no-op is exactly the kind this codebase keeps finding.
     */
    @Test fun `the whole predicate is parenthesised so a later AND cannot rebind`() {
        assertTrue("must be wrapped as one unit: $selection", selection.startsWith("(") && selection.endsWith(")"))
    }
}
