// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one place the path split rules live.
 *
 * Two of these are load-bearing non-folds that have silently regressed twice in this repo, and a
 * non-fold is exactly the assertion that passes when the code under it does nothing — so each is
 * stated as a NON-COLLAPSE OF TWO NAMED INPUTS asserted as a pair (global constraint 10), not as
 * a single-input round trip.
 */
class PathSegmentsTest {

    @Test fun `a volume-root path is a bare separator and yields NO segments`() {
        // Device-observed 2026-08-14: MediaStore records RELATIVE_PATH="/" for a file at the
        // volume root — not "" and not null. A naive split('/') yields ["", ""], which becomes
        // two blank-named folders at the tree root. This is the single sharpest case here.
        assertEquals(emptyList<String>(), PathSegments.split("/"))
    }

    @Test fun `a trailing separator does not create an empty trailing segment`() {
        // Device-observed: RELATIVE_PATH ALWAYS carries a trailing '/'. Every real input hits this.
        assertEquals(listOf("Music", "Beck"), PathSegments.split("Music/Beck/"))
    }

    @Test fun `separator runs collapse but segment contents never do`() {
        assertEquals(listOf("Music", "Beck"), PathSegments.split("Music//Beck"))
    }

    @Test fun `leading whitespace in a segment SURVIVES — two real directories stay two`() {
        val padded = PathSegments.split(" Music/a")
        val plain = PathSegments.split("Music/a")
        assertEquals(
            "' Music' and 'Music' collapsed — they are two directories on ext4/f2fs",
            listOf(listOf(" Music", "a"), listOf("Music", "a")),
            listOf(padded, plain),
        )
    }

    @Test fun `case in a segment SURVIVES — two real directories stay two`() {
        val lower = PathSegments.split("Music/beck")
        val upper = PathSegments.split("Music/Beck")
        assertEquals(
            "'beck' and 'Beck' collapsed — folder identity is byte-exact, never normalized",
            listOf(listOf("Music", "beck"), listOf("Music", "Beck")),
            listOf(lower, upper),
        )
    }

    @Test fun `an empty path yields no segments`() {
        assertEquals(emptyList<String>(), PathSegments.split(""))
    }
}
