// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.replaygain

import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.data.net.ReplayGainLookup
import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Where [ReplayGainResolver] looks for each kind of track, and what it remembers. Robolectric only
 *  because `VeldtUri.parse` decodes with `android.net.Uri`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReplayGainResolverTest {

    private val values = ReplayGainValues(-6f, 0.9f, -7f, 1f)

    private fun song(sourceId: String, externalId: String, uri: String, filePath: String? = null) = Song(
        id = 1, sourceId = sourceId, externalId = externalId, uri = uri, filePath = filePath,
        relativeKey = null, title = "t", artist = "a", album = "b", albumArtist = null,
        trackNumber = null, discNumber = null, year = null, durationMs = 1000, dateModifiedSec = 0,
        hasEmbeddedArt = false,
    )

    private val local = song("local", "42", "content://media/external/audio/media/42", "/music/a.flac")
    private val remote = song("acct-1", "tr-9", VeldtUri.track("acct-1", "tr-9"))

    private val localReads = mutableListOf<String?>()
    private val serverReads = mutableListOf<TrackRef>()
    private var localAnswer: ReplayGainValues? = values
    private var serverAnswer: ReplayGainLookup = ReplayGainLookup.Answered(values)

    private val resolver = ReplayGainResolver(
        readLocal = { path -> localReads += path; localAnswer },
        readServer = { ref -> serverReads += ref; serverAnswer },
    )

    @Test fun `a local track reads its own file's tags, and never the server`() = runTest {
        assertEquals(values, resolver.valuesFor(local))
        assertEquals(listOf<String?>("/music/a.flac"), localReads)
        assertEquals(emptyList<TrackRef>(), serverReads)
    }

    @Test fun `a server track asks its own server, and never reads a file`() = runTest {
        assertEquals(values, resolver.valuesFor(remote))
        assertEquals(listOf(TrackRef("acct-1", "tr-9")), serverReads)
        assertEquals(emptyList<String?>(), localReads)
    }

    @Test fun `answers are remembered, including none`() = runTest {
        localAnswer = null
        assertNull(resolver.valuesFor(local))
        assertNull(resolver.valuesFor(local))
        serverAnswer = ReplayGainLookup.Answered(null)
        assertNull(resolver.valuesFor(remote))
        assertNull(resolver.valuesFor(remote))
        assertEquals("one read per track", 1, localReads.size)
        assertEquals("one request per track", 1, serverReads.size)
    }

    @Test fun `an unreachable server is asked again next time`() = runTest {
        serverAnswer = ReplayGainLookup.Unreachable
        assertNull(resolver.valuesFor(remote))
        serverAnswer = ReplayGainLookup.Answered(values)
        assertEquals(values, resolver.valuesFor(remote))
        assertEquals(2, serverReads.size)
    }
}
