// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The service-side half of the logical playback URI (spec §4.4).
 *
 * Every assertion here is about a *passthrough*: the resolver's job is overwhelmingly to not act.
 * A non-`veldt` uri, a `veldt` uri no resolver claims, and a resolver that declines all return the
 * input, because the alternative is a request for something the user did not ask for.
 *
 * The second half pins the construction-time guards and the [RemoteResolverLookup] fallback that
 * N2 added: static resolvers are refused on the same terms `SourceRegistry` refuses a source id,
 * and the lookup is a fallback, never an override.
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
    private val remote = VeldtUri.track("acct1", "AL/42")

    @Test fun `a content uri is returned unchanged`() {
        // Global constraint 5: local playback is not routed through anything new. The resolver
        // returns the very reference it was handed, so this is identity, not reconstruction.
        //
        // `assertSame`, as ResolvingDataSourceWiringTest already does for DataSpec. assertEquals
        // could not see the claim the KDoc makes: a reconstruction through android.net.Uri is a
        // different object, and not a harmless one — it re-encodes, so `veldt://track/a/b/c`
        // comes back as `.../b%2Fc` and lowercase escapes come back uppercased. A passthrough
        // that silently changes then sends VeldtDataSpecResolver down its rewrite branch.
        val subject = PlaybackUriResolver(setOf(FakeResolver("acct1", "https://WRONG/")))
        assertSame(local, subject.resolve(local))
    }

    @Test fun `a veldt uri with a matching resolver returns that resolver's output`() {
        val subject = PlaybackUriResolver(
            setOf(FakeResolver("acct1", "https://nav.example/rest/stream?id=AL%2F42")),
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
            listOf("veldt://track/acct1/AL%2F42", null),
            listOf(returned, other.consultedWith),
        )
    }

    @Test fun `a resolver returning null falls back to the input unchanged`() {
        // A source that is registered but cannot answer right now — logged out, token expired.
        // Same passthrough as an absent one, and for the same reason.
        val declining = FakeResolver("acct1", null)
        val subject = PlaybackUriResolver(setOf(declining))
        assertEquals(
            "a declining resolver collapsed the uri instead of passing it through",
            listOf<Any?>("veldt://track/acct1/AL%2F42", TrackRef("acct1", "AL/42")),
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
                "veldt://track/acct1/AL%2F42",
            ),
            listOf(subject.resolve(local), subject.resolve(remote)),
        )
    }

    // ---- Construction guards and the lookup fallback (N2 Task 4, carried gap 2) ----

    /** Records every id it was asked about, and answers from a fixed map. */
    private class RecordingLookup(private val answers: Map<String, RemoteUriResolver?>) : RemoteResolverLookup {
        val askedFor = mutableListOf<String>()
        override fun resolverFor(sourceId: String): RemoteUriResolver? {
            askedFor += sourceId
            return answers[sourceId]
        }
    }

    @Test fun `two static resolvers claiming one sourceId are refused, naming the id`() {
        // associateBy would otherwise keep whichever came LAST out of an unordered Hilt set, so
        // which account's credentials served a track would depend on set iteration order.
        val e = assertThrows(IllegalArgumentException::class.java) {
            PlaybackUriResolver(setOf(FakeResolver("acct1", "https://a/"), FakeResolver("acct1", "https://b/")))
        }
        assertEquals("duplicate RemoteUriResolver sourceIds: [acct1]", e.message)
    }

    @Test fun `a static sourceId that is blank or contains a slash or colon is refused`() {
        // The same ban SourceRegistry enforces: '/' breaks VeldtUri's first-slash split and ':'
        // breaks the `sourceId:externalId` media id. Asserted as the full list of (id, refused).
        val ids = listOf("", " ", "a/b", "a:b", "acct1")
        assertEquals(
            listOf("" to true, " " to true, "a/b" to true, "a:b" to true, "acct1" to false),
            ids.map { id ->
                id to runCatching { PlaybackUriResolver(setOf(FakeResolver(id, null))) }
                    .exceptionOrNull().let { it is IllegalArgumentException }
            },
        )
    }

    @Test fun `the refusal message has the SourceRegistry shape`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            PlaybackUriResolver(setOf(FakeResolver("a:b", null)))
        }
        assertEquals("RemoteUriResolver sourceId \"a:b\" must be non-blank and contain no ':' or '/'", e.message)
    }

    @Test fun `the lookup is consulted only for an id no static resolver claims`() {
        val fromLookup = FakeResolver("acct2", "https://lookup/")
        val lookup = RecordingLookup(mapOf("acct2" to fromLookup))
        val subject = PlaybackUriResolver(setOf(FakeResolver("acct1", "https://static/")), lookup)
        val results = listOf(
            subject.resolve(VeldtUri.track("acct1", "x")),
            subject.resolve(VeldtUri.track("acct2", "x")),
        )
        assertEquals(
            "the lookup overrode a static resolver, or was not consulted for an unclaimed id",
            listOf<Any?>(listOf("https://static/", "https://lookup/"), listOf("acct2")),
            listOf<Any?>(results, lookup.askedFor),
        )
    }

    @Test fun `a lookup returning null leaves the uri unchanged`() {
        val lookup = RecordingLookup(emptyMap())
        val subject = PlaybackUriResolver(emptySet(), lookup)
        val returned = subject.resolve(remote)
        assertSame(remote, returned)
        assertEquals(listOf("acct1"), lookup.askedFor)
    }

    @Test fun `a non-veldt uri never reaches the lookup`() {
        // Global constraint 5: a content:// load must not cost a lookup (which, in production,
        // is a map read on SubsonicSources — cheap, but not nothing, on every local track).
        val lookup = RecordingLookup(emptyMap())
        assertSame(local, PlaybackUriResolver(emptySet(), lookup).resolve(local))
        assertEquals(emptyList<String>(), lookup.askedFor)
    }
}
