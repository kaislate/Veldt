// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The playback error policy (spec §7.3).
 *
 * No Robolectric and no `@Config`: the subject is a pure `Int -> enum` function, and the
 * `PlaybackException.ERROR_CODE_*` names are Java `static final int` constants that Kotlin
 * inlines at compile time, so nothing here loads an Android class or boots a player.
 *
 * The codes below were read from the artifact actually on this module's classpath —
 * `androidx.media3:media3-common:1.8.0`, resolved on `debugUnitTestRuntimeClasspath` — via
 * `javap -p -constants` on the `classes.jar` inside the resolved `.aar`, not from any plan.
 */
class PlayerErrorPolicyTest {

    /**
     * (1) The pause set, asserted as (code, action) pairs so a regression names the code that
     * moved rather than reporting `false != true`.
     *
     * These two are the whole `ERROR_CODE_IO_NETWORK_CONNECTION_*` family in 1.8.0, and they
     * are the only codes that mean "the request got no answer at all". Same item, tried again
     * once the radio is back, plausibly works — so advancing the queue is exactly wrong.
     */
    @Test
    fun `network codes pause in place`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, // 2001
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, // 2002
        )
        assertEquals(
            "a network code stopped pausing in place, so one Wi-Fi blip skips the queue again",
            codes.map { it to ErrorAction.PAUSE_IN_PLACE },
            codes.map { it to errorAction(it) },
        )
    }

    /**
     * (2) Non-network codes keep today's skip. A dead file stays dead, and an undecodable one
     * stays undecodable: retrying either in place is a guaranteed dead end.
     *
     * This covers **the entire IO block (2000..2008) except the two that pause**, plus a decoder
     * and a parser code, rather than a sample of it. A sample was the original mistake: the KDoc
     * on [errorAction] rules explicitly on 2000, 2003 and 2007, and none of them were pinned, so
     * moving all three into the pause set left the suite green. Pinning the whole block also
     * means a future Media3 filling 2009 is caught by the unknown-code test below rather than
     * slipping between the two.
     *
     * 2000 is the one that matters most and is least obviously a network code:
     * `ERROR_CODE_IO_UNSPECIFIED` covers **plain local IO**. Ruling it into the pause set would
     * pause *local* playback in place — an unmounted SD card, a file yanked from under a stale
     * MediaStore row — with no retry, by `PAUSE_IN_PLACE`'s own design.
     */
    @Test
    fun `non-network codes skip`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED, // 2000 — also covers plain local IO
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, // 2003 — server answered
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, // 2005
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION, // 2006
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, // 2007 — config fault
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, // 2008 — closes the block
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, // 3001
            PlaybackException.ERROR_CODE_DECODING_FAILED, // 4003
        )
        assertEquals(
            "a non-network code started pausing, so a permanently dead item now stalls playback",
            codes.map { it to ErrorAction.SKIP },
            codes.map { it to errorAction(it) },
        )
    }

    /**
     * The judgement call this task exists for. `ERROR_CODE_IO_BAD_HTTP_STATUS` (2004) is raised
     * for *any* non-2xx, so it conflates 401/429/503 with 404/410 — the response code that would
     * separate them lives on the cause, not on `errorCode`, so this function cannot see it.
     *
     * It skips, for three reasons:
     *  - The server answered. That is positive evidence the network is *up*, which disproves the
     *    premise the pause branch rests on ("the next item will fail identically").
     *  - The two mistakes are not symmetric. Skipping a retry-worthy item costs one item and
     *    playback continues; pausing on a permanently-404 item stops dead, and since pause has no
     *    retry loop, the user's next tap on play re-prepares the same dead item and pauses again.
     *  - The 401-on-expired-token case is real but is not reachable in N0: there is no account, no
     *    credential store and no token refresh, so pausing would be waiting for a refresh nothing
     *    performs. When that arrives it needs the *response code* off the cause, not a blanket
     *    pause keyed on 2004.
     */
    @Test
    fun `bad http status skips rather than pausing on a permanently missing file`() {
        assertEquals(
            "2004 covers 404 as well as 401; pausing on it strands the queue on a dead URL",
            ErrorAction.SKIP,
            errorAction(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
        )
    }

    /**
     * (3) The branch a future Media3 exercises. 2009 is deliberately chosen: it is unassigned in
     * 1.8.0 (the IO block runs 2000..2008, then jumps to 3001) and it is *adjacent to the pause
     * set*, so a range-based implementation — `in 2000..2009` — that a distant code like 1000007
     * would let through is caught here.
     */
    @Test
    fun `an unrecognised code defaults to skip`() {
        val futureIoCode = 2009 // unassigned in media3-common 1.8.0; verified against javap output
        val customCode = PlaybackException.CUSTOM_ERROR_CODE_BASE + 7
        val codes = listOf(futureIoCode, customCode)
        assertEquals(
            "an unknown code must fall to the safe default; pausing on one stalls on anything new",
            codes.map { it to ErrorAction.SKIP },
            codes.map { it to errorAction(it) },
        )
    }

    /** (4) The policy must still distinguish. Either collapse passes tests 1 or 2 alone. */
    @Test
    fun `the policy has not collapsed to a single action`() {
        assertNotEquals(
            "the policy collapsed: a network code and a dead file now get the same action",
            errorAction(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
            errorAction(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND),
        )
    }

    /**
     * The counter rule, extracted so it is assertable at all. A pause that walked
     * `consecutiveErrors` would hit the `>= mediaItemCount` bound during a long outage and stop
     * playback — the same defect wearing a different hat.
     *
     * This pins the *rule*; that `PlaybackConnection.onPlayerError` assigns the field from this
     * function and from nowhere else stays reading-verified (see the report).
     */
    @Test
    fun `pausing does not advance the consecutive error count and skipping does`() {
        val cases = listOf(
            Triple(0, ErrorAction.PAUSE_IN_PLACE, 0),
            Triple(3, ErrorAction.PAUSE_IN_PLACE, 3),
            Triple(0, ErrorAction.SKIP, 1),
            Triple(3, ErrorAction.SKIP, 4),
            // A rejected password has not consumed an item either: the track is fine, the
            // account is not. Stopping must leave the count alone, like pausing.
            Triple(0, ErrorAction.STOP, 0),
            Triple(3, ErrorAction.STOP, 3),
        )
        assertEquals(
            "the counter rule changed: a long outage can walk the bound and stop playback",
            cases.map { (current, action, expected) -> Triple(current, action, expected) },
            cases.map { (current, action, _) ->
                Triple(current, action, nextConsecutiveErrors(current, action))
            },
        )
    }

    /**
     * The two codes N2's `SubsonicErrorGuard` raises (Task 4). They are custom codes
     * (`CUSTOM_ERROR_CODE_BASE` + 401 / + 500), because the server answered HTTP 200 and Media3 has
     * no code for "the 200 was an error envelope".
     *
     * AUTH stops: every item in the queue is on the same account, so skipping would walk the whole
     * queue into the same rejection, one track at a time. REFUSED skips: the server answered and
     * refused THIS item (code 70, not found), which is a property of the item.
     */
    @Test
    fun `the remote codes stop on a rejected password and skip on a refused item`() {
        assertEquals(
            listOf(1_000_401 to ErrorAction.STOP, 1_000_500 to ErrorAction.SKIP),
            listOf(ERROR_CODE_REMOTE_AUTH, ERROR_CODE_REMOTE_REFUSED).map { it to errorAction(it) },
        )
    }

    /**
     * TOTAL table: every code this suite names, and its action, in one assertion — so moving any
     * one of them names that code, and adding an action without placing every named code fails
     * here rather than in whichever sub-test happened to cover it.
     */
    @Test
    fun `every named code maps to exactly its action`() {
        val table = listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED to ErrorAction.PAUSE_IN_PLACE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT to ErrorAction.PAUSE_IN_PLACE,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE to ErrorAction.SKIP,
            2009 to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED to ErrorAction.SKIP,
            PlaybackException.ERROR_CODE_DECODING_FAILED to ErrorAction.SKIP,
            PlaybackException.CUSTOM_ERROR_CODE_BASE + 7 to ErrorAction.SKIP,
            ERROR_CODE_REMOTE_AUTH to ErrorAction.STOP,
            ERROR_CODE_REMOTE_REFUSED to ErrorAction.SKIP,
        )
        assertEquals(table, table.map { (code, _) -> code to errorAction(code) })
    }
}
