// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.ui.settings.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM — the form model deliberately contains no Compose and no Android type. */
class AccountFormTest {

    @Test fun `an https url is valid and warns about nothing`() {
        assertEquals(
            UrlVerdict.Secure("https://music.example.com"),
            AccountForm.judge("https://music.example.com"),
        )
    }

    @Test fun `an http url is valid AND warns`() {
        // Not an error. The owner's decision is to allow cleartext with a warning, because a
        // LAN or Tailscale server has no certificate and refusing it would lock out the
        // common self-hosted case.
        assertEquals(
            UrlVerdict.Cleartext("http://192.168.50.111:4533"),
            AccountForm.judge("http://192.168.50.111:4533"),
        )
    }

    @Test fun `a bare host and port defaults to http and therefore warns`() {
        assertEquals(
            UrlVerdict.Cleartext("http://192.168.50.111:4533"),
            AccountForm.judge("192.168.50.111:4533"),
        )
    }

    @Test fun `surrounding whitespace and a trailing slash are forgiven`() {
        assertEquals(
            UrlVerdict.Cleartext("http://h:4533"),
            AccountForm.judge("  http://h:4533/  "),
        )
    }

    @Test fun `every unusable address is Invalid and none of them throws`() {
        val cases = listOf("", "   ", "ftp://h", "http://", "javascript:alert(1)")
        val notInvalid = cases.filterNot { AccountForm.judge(it) is UrlVerdict.Invalid }
        assertEquals("these should have been Invalid", emptyList<String>(), notInvalid)
    }

    @Test fun `the form is submittable only when url, username and password are all present`() {
        // Enumerated rather than counted: a rule that fires on the wrong FIELD would keep any
        // count identical.
        val cases = listOf(
            Triple("http://h:4533", "Kyle", "pw") to true,
            Triple("http://h:4533", "Kyle", "") to false,
            Triple("http://h:4533", "", "pw") to false,
            Triple("", "Kyle", "pw") to false,
            Triple("ftp://h", "Kyle", "pw") to false,
            Triple("http://h:4533", "  ", "pw") to false,
        )
        val wrong = cases.filter { (input, expected) ->
            val (url, user, pass) = input
            AccountForm.canSubmit(url, user, pass) != expected
        }
        assertEquals("these judged wrongly", emptyList<Pair<Triple<String, String, String>, Boolean>>(), wrong)
    }

    @Test fun `an existing account may be saved without retyping the password`() {
        // Editing a URL must not force a re-type of something the user cannot see.
        assertEquals(true, AccountForm.canSubmitEdit("http://h:4533", "Kyle", password = ""))
        assertEquals(false, AccountForm.canSubmitEdit("", "Kyle", password = ""))
        assertEquals(false, AccountForm.canSubmitEdit("http://h:4533", "", password = ""))
    }

    @Test fun `a display name defaults to the host when left blank`() {
        assertEquals("192.168.50.111", AccountForm.defaultName("http://192.168.50.111:4533"))
        assertEquals("music.example.com", AccountForm.defaultName("https://music.example.com/sub"))
        // The plan asserted `defaultName("nonsense") == "Server"`. It does not and must not: a
        // bare word is a bare HOST, which `normalizeBase` accepts by the same rule that makes
        // the bare host-and-port case above valid, so "nonsense" normalises to
        // `http://nonsense` and yields itself. `http://navidrome` is a real LAN address; a user
        // who typed it would have been named "Server" if the plan's spelling had been honoured.
        // That assertion also never reached the fallback branch at all — these two do.
        assertEquals("navidrome", AccountForm.defaultName("navidrome"))
        assertEquals("Server", AccountForm.defaultName("ftp://h"))
    }
}
