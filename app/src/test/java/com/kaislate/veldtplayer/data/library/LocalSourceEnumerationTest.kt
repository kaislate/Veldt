// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.model.Song
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * What [LocalSource.listSongs] actually **emits**, against a real `ContentResolver` serving a
 * fixture cursor.
 *
 * This file exists because of a negative control. Mutating `listSongs`' `sourceId` to a wrong
 * literal turned the whole 494-test suite exactly zero red: every other test in the repo feeds
 * `LocalSource` a hand-built [com.kaislate.veldtplayer.data.library.model.Song], or replaces it
 * with a fake outright, so the one function that translates MediaStore columns into the source
 * dimension was unreachable by any assertion. An emission no test can observe is the same defect
 * as a wrong emission.
 *
 * `LocalSourceKeysTest` deliberately runs without provider setup because `stableKey` and
 * `resolvePlayableUri` never touch MediaStore; this is the complementary half, and it is a separate
 * file so that claim stays true.
 *
 * ### What this can and cannot pin
 *
 * It **cannot** distinguish `sourceId = this@LocalSource.id` from `sourceId = "local"`:
 * [LocalSource.id] is a non-open `val` initialised to that same literal, so both spellings produce
 * identical output and `assertEquals(source.id, song.sourceId)` would be a tautology over the
 * distinction Global Constraint 1 actually cares about. It **can** catch every other drift — a
 * dropped field, a blank, a different literal, a value copied out of the wrong column — which is
 * what the control that prompted this file mutated. The literal-vs-`val` spelling stays a review
 * rule (GC 1: `"local"` appears in production exactly once), not a testable property, and that is
 * stated here rather than papered over with an assertion that cannot fail.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK as the other Robolectric tests do.
@Config(sdk = [34])
class LocalSourceEnumerationTest {

    /** The `content://media/...` authority `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` targets. */
    private val authority = MediaStore.AUTHORITY

    private val columns = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ARTIST,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.YEAR,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATE_MODIFIED,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.VOLUME_NAME,
        MediaStore.Audio.Media.RELATIVE_PATH,
        MediaStore.Audio.Media.DISPLAY_NAME,
    )

    /** Serves whatever cursor the test hands it, for any query. */
    private class FakeMediaProvider : ContentProvider() {
        var cursor: Cursor? = null
        override fun onCreate() = true
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = cursor
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private lateinit var source: LocalSource
    private lateinit var provider: FakeMediaProvider

    @Before fun setUp() {
        provider = FakeMediaProvider()
        ShadowContentResolver.registerProviderInternal(authority, provider)
        source = LocalSource(ApplicationProvider.getApplicationContext())
    }

    /** One MediaStore row. [mediaStoreId] is the `_ID` the provider hands out. */
    private fun row(mediaStoreId: Long, title: String, name: String) = arrayOf<Any?>(
        mediaStoreId, title, "Artist", "Album", null, 1, 2020, 1_000L, 55L,
        "/storage/emulated/0/Music/$name", "external_primary", "Music/", name,
    )

    private fun serve(vararg rows: Array<Any?>) {
        provider.cursor = MatrixCursor(columns).apply { rows.forEach { addRow(it) } }
    }

    /**
     * `externalId` is the row's own `_ID` as text — asserted against the value the **provider**
     * supplied, never against `song.id`, so this cannot pass by the two outputs agreeing with each
     * other. Two rows, asserted as a pair: a constant, a dropped field, or a value taken from the
     * wrong row all collapse the pair and the failure message shows the collapse.
     */
    @Test fun `externalId is each row's own MediaStore _ID as text`() = runTest {
        serve(row(9001L, "Alpha", "a.mp3"), row(9002L, "Beta", "b.mp3"))
        assertEquals(
            listOf("Alpha" to "9001", "Beta" to "9002"),
            source.listSongs().map { it.title to it.externalId },
        )
    }

    /**
     * Every emitted row carries the source dimension, and it is the enumerating source's id.
     * This is what the control that created this file mutated: it becomes red for any wrong or
     * missing value. See the class KDoc for the one substitution it provably cannot see.
     */
    @Test fun `every emitted song carries the enumerating source's id`() = runTest {
        serve(row(9001L, "Alpha", "a.mp3"), row(9002L, "Beta", "b.mp3"))
        assertEquals(
            listOf(source.id, source.id),
            source.listSongs().map { it.sourceId },
        )
        assertNotEquals("", source.id)
    }

    /**
     * The identity fields are not each other's aliases, and the surrogate is **not one of them**.
     * `externalId` is the `_ID` as text and `relativeKey` is the location; `id` is a Room surrogate
     * that this method — reading a source, not the database — cannot know, so it emits
     * [Song.UNSAVED]. Asserting all three of one row against the provider's input pins which column
     * each came from; a mapper that filled `externalId` from the display name, or `relativeKey`
     * from the id, is red here.
     */
    @Test fun `id externalId and relativeKey each come from their own column`() = runTest {
        serve(row(9001L, "Alpha", "a.mp3"))
        val song = source.listSongs().single()
        assertEquals(Song.UNSAVED, song.id)
        assertEquals("9001", song.externalId)
        assertEquals("external_primary:Music/a.mp3", song.relativeKey)
    }

    /**
     * **The surrogate is not the MediaStore `_ID`, stated as the non-collapse of two named rows.**
     *
     * This is the property the whole task turns on and it is the easiest one to regress by
     * accident: `id = id` reads as obviously correct at the call site, was correct until this
     * commit, and would leave the rest of the suite green — a MediaStore `_ID` is a perfectly
     * plausible surrogate right up to the moment a second source hands out the same number, or
     * `upsertBySourceKey` gets a row claiming an id that already belongs to a different track.
     *
     * Two rows with **different** `_ID`s must produce the **same** `id`, because both are unsaved.
     * That is the assertion a lingering `id = _ID` cannot satisfy: it would emit `9001` and `9002`.
     * Asserted as a list against the literal sentinel, so the failure message shows the `_ID`s
     * leaking through. `assertEquals(0L, …)` on a single row would pass just as well for a source
     * that happened to enumerate one file with `_ID` 0.
     */
    @Test fun `every enumerated song is UNSAVED, whatever its MediaStore _ID`() = runTest {
        serve(row(9001L, "Alpha", "a.mp3"), row(9002L, "Beta", "b.mp3"))
        assertEquals(
            listOf(Song.UNSAVED, Song.UNSAVED),
            source.listSongs().map { it.id },
        )
    }
}
