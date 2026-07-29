// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    private fun song(id: Long, filePath: String?) = Song(
        id = id,
        uri = "content://media/external/audio/media/$id",
        filePath = filePath,
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

    @Test fun `stableKey is the MediaStore DATA path`() {
        assertEquals("/storage/emulated/0/Music/a.mp3", source.stableKey(song(3, "/storage/emulated/0/Music/a.mp3")))
    }

    @Test fun `stableKey and resolvePlayableUri are not the same string`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertNotEquals(source.stableKey(s), source.resolvePlayableUri(s))
    }

    /**
     * DATA is withheld by some providers on API 29+. Falling back to the uri leaves such an entry
     * exactly as well off as it would have been anyway, rather than throwing or keying on null.
     */
    @Test fun `stableKey falls back to the uri when the path is absent`() {
        val s = song(3, filePath = null)
        assertEquals("content://media/external/audio/media/3", source.stableKey(s))
    }

    @Test fun `resolvePlayableUri is unchanged — it is still the content uri`() {
        val s = song(3, "/storage/emulated/0/Music/a.mp3")
        assertEquals("content://media/external/audio/media/3", source.resolvePlayableUri(s))
    }
}
