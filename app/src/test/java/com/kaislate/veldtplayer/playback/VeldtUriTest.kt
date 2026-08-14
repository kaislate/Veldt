// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The logical playback URI (spec §4.4).
 *
 * `externalId` is SERVER-controlled — a Navidrome id, a Jellyfin GUID, anything a future backend
 * returns — so it may legally contain `/`, `%`, `?`, `#` and spaces. Encoding is mandatory, and
 * these assertions check the ENCODED FORM rather than a decode round trip: a round trip proves the
 * function is invertible, not that the intermediate is correct. That exact gap shipped a Critical
 * on the P1.6 branch, where `Uri.encode(key, "/")` left `/` bare, passed every test, and would have
 * crashed every folder tap.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as VeldtArtUriTest does.
@Config(sdk = [34])
class VeldtUriTest {

    @Test fun `a track uri encodes BOTH segments, asserted as the literal`() {
        assertEquals(
            "veldt://track/subsonic%3Aacct1/AL%2F42%20%25x%20%23y",
            VeldtUri.track("subsonic:acct1", "AL/42 %x #y"),
        )
    }

    @Test fun `a separator in either segment cannot merge two distinct tracks`() {
        // Stated as a non-collapse pair: these two inputs differ only in WHERE the slash sits, and
        // an unencoded scheme maps both onto the same string.
        val a = VeldtUri.track("s/1", "e")
        val b = VeldtUri.track("s", "1/e")
        assertEquals(
            "an unencoded segment merged two distinct (sourceId, externalId) pairs",
            listOf(false, true),
            listOf(a == b, VeldtUri.parse(a) == TrackRef("s/1", "e")),
        )
    }

    @Test fun `parse recovers both segments verbatim`() {
        val uri = VeldtUri.track("subsonic:acct1", "AL/42 %x #y")
        assertEquals(TrackRef("subsonic:acct1", "AL/42 %x #y"), VeldtUri.parse(uri))
    }

    @Test fun `a content uri is NOT a veldt uri and parses to null`() {
        // The passthrough contract Task 2 depends on. Local playback must not be touched.
        assertNull(VeldtUri.parse("content://media/external/audio/media/122"))
    }

    @Test fun `a malformed veldt uri parses to null rather than throwing`() {
        assertEquals(
            listOf(null, null, null),
            listOf(
                VeldtUri.parse("veldt://track/onlyonesegment"),
                VeldtUri.parse("veldt://album/a/b"),
                VeldtUri.parse("veldt://track//e"),
            ),
        )
    }

    @Test fun `an empty external id is rejected, not encoded into a valid-looking uri`() {
        assertNull(VeldtUri.parse(VeldtUri.track("local", "")))
    }
}
