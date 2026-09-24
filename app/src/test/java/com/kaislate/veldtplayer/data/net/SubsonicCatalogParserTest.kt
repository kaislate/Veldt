// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import com.kaislate.veldtplayer.data.library.DisplayNames
import com.kaislate.veldtplayer.data.library.model.Song
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric — [VeldtUri] uses `android.net.Uri`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicCatalogParserTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun `album ids come from albumList2 in server order`() {
        val body = obj("""{"albumList2":{"album":[{"id":"a1","name":"X"},{"id":"a2","name":"Y"}]}}""")
        assertEquals(listOf("a1", "a2"), SubsonicCatalogParser.albumIds(body))
    }

    @Test fun `an empty album list has no album key at all and parses as empty`() {
        // Navidrome omits the array entirely for an empty page, not "album":[]
        assertEquals(emptyList<String>(), SubsonicCatalogParser.albumIds(obj("""{"albumList2":{}}""")))
    }

    @Test fun `a song maps every field the projection stores`() {
        val body = obj(
            """{"album":{"id":"al1","name":"Alb","artist":"The Album Artist","song":[
            {"id":"s1","title":"T","album":"Alb","artist":"Trk Artist","track":3,"discNumber":2,
             "year":2011,"duration":245,"coverArt":"mf-s1"}]}}""",
        )
        val song = SubsonicCatalogParser.songs(body, "acct-1").single()
        assertEquals(
            Song(
                id = Song.UNSAVED, sourceId = "acct-1", externalId = "s1",
                uri = VeldtUri.track("acct-1", "s1"), filePath = null, relativeKey = null,
                title = "T", artist = "Trk Artist", album = "Alb", albumArtist = "The Album Artist",
                trackNumber = 3, discNumber = 2, year = 2011, durationMs = 245_000L,
                dateModifiedSec = 0L, hasEmbeddedArt = true,
            ),
            song,
        )
    }

    @Test fun `missing tags fall back to the same display names local files use`() {
        val body = obj("""{"album":{"id":"al1","song":[{"id":"s1"}]}}""")
        val song = SubsonicCatalogParser.songs(body, "acct-1").single()
        assertEquals(
            listOf(DisplayNames.UNKNOWN_TITLE, DisplayNames.UNKNOWN_ARTIST, DisplayNames.UNKNOWN_ALBUM, null, false, 0L),
            listOf(song.title, song.artist, song.album, song.albumArtist, song.hasEmbeddedArt, song.durationMs),
        )
    }

    @Test fun `a song without an id is dropped rather than keyed on nothing`() {
        val body = obj("""{"album":{"id":"al1","song":[{"title":"no id"},{"id":"s2","title":"ok"}]}}""")
        assertEquals(listOf("s2"), SubsonicCatalogParser.songs(body, "a").map { it.externalId })
    }
}
