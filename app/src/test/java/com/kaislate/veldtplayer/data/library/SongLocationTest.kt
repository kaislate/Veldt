// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A song's place on disk, resolved by the same two-rung ladder as `LocalSource.stableKey` and for
 * the same reason: rung 1 is volume-qualified by construction, rung 2 is an absolute path whose
 * volume must be inferred, and inference can fail while rung 1 cannot.
 *
 * The rung-2 case is NOT belt-and-braces. Device-observed 2026-08-14: a file at the volume root
 * gets `RELATIVE_PATH == "/"`, `composeRelativeKey` trims that to `""` and returns null, so such a
 * track reaches the tree ONLY via `filePath`. Proven with `/sdcard/veldt-root-probe.mp3`.
 */
class SongLocationTest {

    private fun song(relativeKey: String?, filePath: String?) = Song(
        id = 1L, sourceId = "test", externalId = "1", uri = "content://x/1",
        filePath = filePath, relativeKey = relativeKey,
        title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null,
        durationMs = 0L, dateModifiedSec = 0L, hasEmbeddedArt = false,
    )

    @Test fun `rung 1 splits a relativeKey into volume, directories and file name`() {
        val loc = song("external_primary:Music/Beck/Lost Cause.mp3", null).location()
        assertEquals(
            SongLocation("external_primary", listOf("Music", "Beck"), "Lost Cause.mp3"),
            loc,
        )
    }

    @Test fun `rung 1 is preferred over rung 2 when both are present`() {
        val loc = song("external_primary:Music/A.mp3", "/storage/emulated/0/Elsewhere/A.mp3").location()
        assertEquals(listOf("Music"), loc?.segments)
    }

    @Test fun `rung 2 recovers a volume-root track, which rung 1 CANNOT represent`() {
        // composeRelativeKey returns null here by construction — this is the concrete proof that
        // the filePath rung is load-bearing. Segments are empty: the file IS at the volume root.
        val loc = song(null, "/storage/emulated/0/veldt-root-probe.mp3").location()
        assertEquals(
            SongLocation("external_primary", emptyList(), "veldt-root-probe.mp3"),
            loc,
        )
    }

    @Test fun `rung 2 maps the primary mount prefix to the primary volume name`() {
        val loc = song(null, "/storage/emulated/0/Music/Beck/x.mp3").location()
        assertEquals("external_primary", loc?.volume)
        assertEquals(listOf("Music", "Beck"), loc?.segments)
    }

    @Test fun `rung 2 on an UNRECOGNISED mount keeps the path but marks the volume unknown`() {
        // Degradation, not a drop: the track still has a place in the tree. Device-unverifiable —
        // no SD card exists on this fleet (pre-flight, 2026-08-14).
        val loc = song(null, "/storage/1234-5678/Music/x.mp3").location()
        assertEquals(VOLUME_UNKNOWN, loc?.volume)
        assertEquals(listOf("storage", "1234-5678", "Music"), loc?.segments)
    }

    @Test fun `both rungs null yields no location — the Unfiled case`() {
        assertNull(song(null, null).location())
    }

    @Test fun `a segment's whitespace and case survive the ladder`() {
        val padded = song("external_primary: Music/a.mp3", null).location()
        val plain = song("external_primary:Music/a.mp3", null).location()
        assertEquals(
            "' Music' and 'Music' collapsed on the way through SongLocation",
            listOf(listOf(" Music"), listOf("Music")),
            listOf(padded?.segments, plain?.segments),
        )
    }
}
