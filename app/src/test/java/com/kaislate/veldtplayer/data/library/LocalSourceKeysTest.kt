// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `stableKey` vs `resolvePlayableUri` — two different questions, and the whole reason playlist
 * entries survive a rescan.
 *
 * Neither function touches MediaStore, so these run against a real [LocalSource] without any
 * provider setup; Robolectric is here only to supply the Context the constructor takes.
 *
 * This is the guard the playlist tests structurally cannot provide: they use a fake source, so a
 * regression in *this* implementation would be invisible to them.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin the SDK so this starts under targetSdk 36.
@Config(sdk = [34])
class LocalSourceKeysTest {

    private lateinit var source: LocalSource

    @Before fun setUp() {
        source = LocalSource(ApplicationProvider.getApplicationContext())
    }

    private fun song(id: Long, filePath: String?, relativeKey: String? = null) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = filePath,
        relativeKey = relativeKey,
        title = "Alpha",
        artist = "Artist",
        album = "Album",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = 1000L,
        dateModifiedSec = 0L,
        hasEmbeddedArt = false,
    )

    /**
     * THE property. A rescan deletes and reinserts the row under a new MediaStore `_ID` — same
     * file, same path. The stable key must not notice. Keyed on the uri it would change, and the
     * playlist entry pointing at it would go permanently blank.
     */
    @Test fun `the same file under a different MediaStore id yields the same stable key`() {
        val before = song(id = 3, filePath = "/storage/emulated/0/Music/a.mp3")
        val after = song(id = 7, filePath = "/storage/emulated/0/Music/a.mp3")
        assertEquals(source.stableKey(before), source.stableKey(after))
        // ...whereas the thing we deliberately did NOT key on does change.
        assertNotEquals(source.resolvePlayableUri(before), source.resolvePlayableUri(after))
    }

    // ---- the three-rung ladder --------------------------------------------------------------

    /** Rung 1 wins outright: it is the guaranteed-present, non-deprecated one. */
    @Test fun `stableKey prefers the relative key over the DATA path`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3", relativeKey = "Music/a.mp3")
        assertEquals("Music/a.mp3", source.stableKey(s))
    }

    /** Rung 2: DATA is fully qualified when present, and is what the tag reader already uses. */
    @Test fun `stableKey falls back to the DATA path when there is no relative key`() {
        assertEquals(
            "/storage/emulated/0/Music/a.mp3",
            source.stableKey(song(3, "/storage/emulated/0/Music/a.mp3", relativeKey = null)),
        )
    }

    /**
     * Rung 1 present, rung 2 absent — the case that used to be silent. Before this round a row
     * with no DATA fell straight to the uri and carried a key that could not survive a rescan,
     * with nothing anywhere to surface it.
     */
    @Test fun `stableKey works when DATA is withheld but the relative key is present`() {
        val s = song(3, filePath = null, relativeKey = "Music/a.mp3")
        assertEquals("Music/a.mp3", source.stableKey(s))
        assertNotEquals(source.resolvePlayableUri(s), source.stableKey(s))
    }

    /** The same file, no DATA at all, before and after a rescan reissues its id. */
    @Test fun `a file with no DATA path still keys stably across an id reissue`() {
        val before = song(3, filePath = null, relativeKey = "Music/a.mp3")
        val after = song(7, filePath = null, relativeKey = "Music/a.mp3")
        assertEquals(source.stableKey(before), source.stableKey(after))
        assertNotEquals(source.resolvePlayableUri(before), source.resolvePlayableUri(after))
    }

    @Test fun `stableKey and resolvePlayableUri are not the same string`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertNotEquals(source.stableKey(s), source.resolvePlayableUri(s))
    }

    /**
     * Rung 3, and only when both location columns are gone. This entry does NOT survive a rescan
     * — see R3-C1 — but falling back beats throwing or keying on null, and with rung 1 available
     * from minSdk 29 it should be unreachable in practice.
     */
    @Test fun `stableKey falls back to the uri only when both location rungs are absent`() {
        val s = song(3, filePath = null, relativeKey = null)
        assertEquals("content://media/external/audio/media/3", source.stableKey(s))
    }

    @Test fun `resolvePlayableUri is unchanged — it is still the content uri`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertEquals("content://media/external/audio/media/3", source.resolvePlayableUri(s))
    }

    // ---- composing the relative key from the two MediaStore columns --------------------------

    /**
     * `RELATIVE_PATH` conventionally carries a trailing separator. If the composer did not
     * normalise it, the same file would key differently depending on what the provider returned,
     * and a playlist would stop resolving after an OS update changed its mind about the slash.
     */
    @Test fun `composeRelativeKey inserts exactly one separator whatever the input`() {
        val expected = "Music/Beck/Lost Cause.mp3"
        assertEquals(expected, LocalSource.composeRelativeKey("Music/Beck/", "Lost Cause.mp3"))
        assertEquals(expected, LocalSource.composeRelativeKey("Music/Beck", "Lost Cause.mp3"))
        assertEquals(expected, LocalSource.composeRelativeKey("/Music/Beck/", "Lost Cause.mp3"))
        assertEquals(expected, LocalSource.composeRelativeKey("  Music/Beck/  ", " Lost Cause.mp3 "))
    }

    /**
     * A bare display name is deliberately NOT a key. `EXTERNAL_CONTENT_URI` spans volumes on API
     * 29+, so `Lost.mp3` alone collides across directories and could resolve an entry to the wrong
     * file. Returning null falls through to the fully-qualified DATA path instead, which is
     * strictly safer than a confidently wrong match.
     */
    @Test fun `composeRelativeKey returns null unless both parts are present`() {
        assertNull(LocalSource.composeRelativeKey(null, "Lost.mp3"))
        assertNull(LocalSource.composeRelativeKey("", "Lost.mp3"))
        assertNull(LocalSource.composeRelativeKey("   ", "Lost.mp3"))
        assertNull(LocalSource.composeRelativeKey("/", "Lost.mp3"))
        assertNull(LocalSource.composeRelativeKey("Music/Beck/", null))
        assertNull(LocalSource.composeRelativeKey("Music/Beck/", "  "))
        assertNull(LocalSource.composeRelativeKey(null, null))
    }

    /** Two different files in the same folder must not collapse onto one key. */
    @Test fun `composeRelativeKey distinguishes files within a folder and folders across files`() {
        assertNotEquals(
            LocalSource.composeRelativeKey("Music/Beck/", "a.mp3"),
            LocalSource.composeRelativeKey("Music/Beck/", "b.mp3"),
        )
        assertNotEquals(
            LocalSource.composeRelativeKey("Music/Beck/", "a.mp3"),
            LocalSource.composeRelativeKey("Music/Nick/", "a.mp3"),
        )
    }
}
