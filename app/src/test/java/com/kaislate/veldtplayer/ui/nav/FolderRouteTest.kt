// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.nav

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Folder keys carry `/`, `%`, `#` and spaces, so the encode is load-bearing, not defensive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FolderRouteTest {

    /**
     * The ENCODED FORM, asserted as a literal — the decode assertion below cannot see the one
     * character that matters.
     *
     * `Uri.decode("a/b")` is `"a/b"`, so a round-trip assertion passes just as happily when `/` is
     * left bare — `Uri.encode(key, "/")` encodes the `%`, the `#` and the spaces and satisfies it.
     * That mutant is not cosmetic: `folder/external_primary:Music/Beck` is THREE path segments, and
     * Navigation Compose compiles `folder/{key}` to an argument pattern that does not cross `/`, so
     * no destination matches.
     *
     * **An unmatched route CRASHES; it does not fail quietly.** Checked against the artifact this
     * project actually resolves rather than assumed: `navigation-runtime-android 2.9.0`'s
     * `NavControllerImpl` carries the message *"Navigation destination that matches route … cannot
     * be found in the navigation graph"*, and the constant pool marks it
     * `$i$a$-requireNotNull-NavControllerImpl$navigate$5` — a `requireNotNull` inside `navigate`,
     * which throws `IllegalArgumentException` straight out of the click handler. So the mutant this
     * test exists to kill would have taken the app down on the first folder tap, and until this
     * assertion existed the suite read green through it.
     */
    @Test fun `every separator is percent-encoded, so the route stays one path segment`() {
        assertEquals(
            "the folder key is not fully encoded — a bare '/' splits the route into extra path " +
                "segments and Navigation Compose will not match it",
            "folder/external_primary%3AMusic%2FA%20%25%20B%20%231%2FDisc%202",
            Destinations.folder("external_primary:Music/A % B #1/Disc 2"),
        )
    }

    /** The complement: whatever the encoding is, the key must come back out of it unchanged. */
    @Test fun `a key with separators, percent, hash and spaces survives the round trip`() {
        val key = "external_primary:Music/A % B #1/Disc 2"
        val route = Destinations.folder(key)
        assertEquals(key, Uri.decode(route.removePrefix("folder/")))
    }

    @Test fun `case is preserved through the route — two folders stay two`() {
        assertEquals(
            "a case-only difference collapsed in the route, merging two real directories",
            listOf("external_primary:Music/beck", "external_primary:Music/Beck"),
            listOf(
                Uri.decode(Destinations.folder("external_primary:Music/beck").removePrefix("folder/")),
                Uri.decode(Destinations.folder("external_primary:Music/Beck").removePrefix("folder/")),
            ),
        )
    }
}
