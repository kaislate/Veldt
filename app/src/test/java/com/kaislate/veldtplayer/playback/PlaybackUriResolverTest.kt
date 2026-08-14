// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service-side half of the logical playback URI (spec §4.4).
 *
 * Every assertion here is about a *passthrough*: the resolver's job is overwhelmingly to not act.
 * A non-`veldt` uri, a `veldt` uri no resolver claims, and a resolver that declines all return the
 * input, because the alternative is a request for something the user did not ask for. Only one of
 * the six cases below rewrites anything.
 *
 * Robolectric because [VeldtUri] runs on a real `android.net.Uri` for percent-encoding; the stub
 * returns null for all of it.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as VeldtArtUriTest does.
@Config(sdk = [34])
class PlaybackUriResolverTest {

    /**
     * Records the ref it was consulted with, because test 4 cannot be stated on the returned
     * string alone: "not consulted" and "consulted, returned the input" produce the same string.
     */
    private class FakeResolver(
        override val sourceId: String,
        private val answer: String?,
    ) : RemoteUriResolver {
        var consultedWith: TrackRef? = null

        override fun resolve(ref: TrackRef): String? {
            consultedWith = ref
            return answer
        }
    }

    private val local = "content://media/external/audio/media/122"
    private val remote = VeldtUri.track("subsonic:acct1", "AL/42")

    @Test fun `a content uri is returned unchanged`() {
        // Global constraint 5: local playback is not routed through anything new. The resolver
        // returns the very reference it was handed, so this is identity, not reconstruction.
        //
        // `assertSame`, as ResolvingDataSourceWiringTest already does for DataSpec. assertEquals
        // could not see the claim the KDoc makes: a reconstruction through android.net.Uri is a
        // different object, and not a harmless one — it re-encodes, so `veldt://track/a/b/c`
        // comes back as `.../b%2Fc` and lowercase escapes come back uppercased. A passthrough
        // that silently changes then sends VeldtDataSpecResolver down its rewrite branch.
        val subject = PlaybackUriResolver(setOf(FakeResolver("subsonic:acct1", "https://WRONG/")))
        assertSame(local, subject.resolve(local))
    }

    @Test fun `a veldt uri with a matching resolver returns that resolver's output`() {
        val subject = PlaybackUriResolver(
            setOf(FakeResolver("subsonic:acct1", "https://nav.example/rest/stream?id=AL%2F42")),
        )
        assertEquals("https://nav.example/rest/stream?id=AL%2F42", subject.resolve(remote))
    }

    @Test fun `a veldt uri with no matching resolver returns the input unchanged`() {
        // It will fail to load, which is the correct outcome: visible, and attributable to a
        // missing account rather than to a uri that silently became something else.
        // `assertSame` for the same reason as above — "unchanged" is an identity claim.
        val subject = PlaybackUriResolver(setOf(FakeResolver("jellyfin", "https://WRONG/")))
        assertSame(remote, subject.resolve(remote))
    }

    @Test fun `a resolver whose sourceId does not match is never consulted`() {
        val other = FakeResolver("jellyfin", "https://jellyfin.example/WRONG")
        val returned = PlaybackUriResolver(setOf(other)).resolve(remote)
        // Stated as a non-collapse pair. The string alone cannot separate "not consulted" from
        // "consulted and happened to hand back the input"; the record alone cannot separate
        // "routed correctly" from "routed nowhere at all".
        assertEquals(
            "the wrong source's resolver was consulted",
            listOf("veldt://track/subsonic%3Aacct1/AL%2F42", null),
            listOf(returned, other.consultedWith),
        )
    }

    @Test fun `a resolver returning null falls back to the input unchanged`() {
        // A source that is registered but cannot answer right now — logged out, token expired.
        // Same passthrough as an absent one, and for the same reason.
        val declining = FakeResolver("subsonic:acct1", null)
        val subject = PlaybackUriResolver(setOf(declining))
        assertEquals(
            "a declining resolver collapsed the uri instead of passing it through",
            listOf<Any?>("veldt://track/subsonic%3Aacct1/AL%2F42", TrackRef("subsonic:acct1", "AL/42")),
            listOf<Any?>(subject.resolve(remote), declining.consultedWith),
        )
    }

    @Test fun `an empty resolver set passes everything through`() {
        // This slice's real configuration: the multibinding contributes nothing until N2 adds
        // SubsonicSource, so the whole app runs through this branch.
        val subject = PlaybackUriResolver(emptySet())
        assertEquals(
            listOf(
                "content://media/external/audio/media/122",
                "veldt://track/subsonic%3Aacct1/AL%2F42",
            ),
            listOf(subject.resolve(local), subject.resolve(remote)),
        )
    }
}
