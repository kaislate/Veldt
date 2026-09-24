// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.net.SubsonicAuth
import com.kaislate.veldtplayer.data.net.SubsonicCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * Server accounts as a [RemoteResolverLookup] (N2 Task 4, Step 3).
 *
 * The account side is two fakes — `known` and `credentials` — standing in for
 * `SubsonicSources.contains` and `SubsonicSources.credentials`. **What the real pair forbids that
 * the fakes permit, and what that means here:** in the real class the two answers come from
 * different places (`contains` reads the collector's snapshot, `credentials` reads the row and
 * the Keystore fresh), so they CAN disagree — an account whose secret file is gone is `contains`
 * true, `credentials` null. The fakes let a test choose that disagreement deliberately, which is
 * why `a known account whose credentials are gone declines` exists: it is a state the real class
 * reaches, not one only a fake can. What the fakes permit that the real class does not is any
 * sourceId at all, including ones with `:` or `/`; that is harmless here because this class only
 * ever uses the id as a key.
 *
 * The meter is a fake too; see `StreamQualityTest` for what the real one does differently.
 */
class SubsonicStreamResolversTest {

    private val scopes = mutableListOf<CoroutineScope>()
    @After fun tearDown() = scopes.forEach { it.cancel() }

    private val creds = SubsonicCredentials("http://h:4533", "kyle", "hunter2")

    private fun subject(
        known: Set<String> = setOf("acct1"),
        stored: Map<String, SubsonicCredentials> = mapOf("acct1" to creds),
        metered: Boolean = false,
        cap: Int = 0,
    ): SubsonicStreamResolvers {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return SubsonicStreamResolvers(
            known = { it in known },
            credentials = { stored[it] },
            quality = StreamQuality(MutableStateFlow(cap), { metered }, scope),
            random = Random(7),
        )
    }

    @Test fun `an unknown account has no resolver`() {
        assertNull(subject().resolverFor("acct2"))
    }

    @Test fun `a known account's resolver answers for that account`() {
        assertEquals("acct1", subject().resolverFor("acct1")?.sourceId)
    }

    @Test fun `a known account resolves to a stream url for that track`() {
        val url = subject().resolverFor("acct1")!!.resolve(TrackRef("acct1", "s1"))!!.toHttpUrl()
        assertEquals(
            listOf<Any?>("h", 4533, "/rest/stream", "kyle", "s1"),
            listOf<Any?>(url.host, url.port, url.encodedPath, url.queryParameter("u"), url.queryParameter("id")),
        )
    }

    @Test fun `maxBitRate is sent exactly when metered and capped`() {
        // TOTAL over the two booleans that matter, with a real cap value in the capped rows.
        val rows = listOf(
            Triple(true, 192, "192"),
            Triple(true, 0, null),
            Triple(false, 192, null),
            Triple(false, 0, null),
        )
        assertEquals(
            rows,
            rows.map { (metered, cap, _) ->
                val url = subject(metered = metered, cap = cap).resolverFor("acct1")!!
                    .resolve(TrackRef("acct1", "s1"))!!.toHttpUrl()
                Triple(metered, cap, url.queryParameter("maxBitRate"))
            },
        )
    }

    @Test fun `every resolve mints a fresh salt`() {
        // One salt per request, drawn from the injected RNG, so a token lifted from one request is
        // not the next one's token. The expectation replays the same seed through the same salt
        // function; the distinct() half catches a resolver that drew once and cached the result.
        val resolver = subject().resolverFor("acct1")!!
        val salts = List(3) { resolver.resolve(TrackRef("acct1", "s1"))!!.toHttpUrl().queryParameter("s") }
        val replay = Random(7)
        val expected = List(3) { SubsonicAuth.newSalt(replay) }
        assertEquals(listOf(expected, expected), listOf(salts, salts.distinct()))
    }

    @Test fun `a known account whose credentials are gone declines`() {
        // contains() true, credentials() null: the secret file was deleted or the Keystore key
        // invalidated. Null is the passthrough answer; the logical uri then fails to load visibly.
        val resolver = subject(stored = emptyMap()).resolverFor("acct1")!!
        assertNull(resolver.resolve(TrackRef("acct1", "s1")))
    }
}
