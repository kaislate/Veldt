// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Field values here are MEASURED, not invented: a live Navidrome 0.63.2 answered `ping` with
 * `type=navidrome`, `version=1.16.1`, `serverVersion=0.63.2 (be10f89c)`, `openSubsonic=true`
 * on 2026-08-14, and returned code 10 for absent credentials and code 40 for wrong ones.
 * A fixture is a claim about reachable states; these states were reached.
 */
class SubsonicEnvelopeTest {

    private val okPing = """
        {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
        "serverVersion":"0.63.2 (be10f89c)","openSubsonic":true}}
    """.trimIndent()

    private val wrongPassword = """
        {"subsonic-response":{"status":"failed","version":"1.16.1",
        "error":{"code":40,"message":"Wrong username or password"}}}
    """.trimIndent()

    private val missingParam = """
        {"subsonic-response":{"status":"failed","version":"1.16.1",
        "error":{"code":10,"message":"missing parameter: 'u'"}}}
    """.trimIndent()

    @Test fun `an ok envelope yields the response object itself`() {
        val result = SubsonicEnvelope.parse(okPing)
        val ok = result as? SubsonicResult.Ok ?: error("expected Ok, got $result")
        // Asserted element by element, not by size: a count is blind to a parser that
        // returns the right NUMBER of wrong fields (Global Constraint 14).
        assertEquals(JsonPrimitive("navidrome"), ok.body["type"])
        assertEquals(JsonPrimitive("0.63.2 (be10f89c)"), ok.body["serverVersion"])
        assertEquals(JsonPrimitive(true), ok.body["openSubsonic"])
    }

    @Test fun `wrong credentials parse as error 40`() {
        assertEquals(
            SubsonicResult.Failed(SubsonicError.WRONG_CREDENTIALS, 40, "Wrong username or password"),
            SubsonicEnvelope.parse(wrongPassword),
        )
    }

    @Test fun `absent credentials parse as error 10, NOT as 40`() {
        assertEquals(
            SubsonicResult.Failed(SubsonicError.MISSING_PARAMETER, 10, "missing parameter: 'u'"),
            SubsonicEnvelope.parse(missingParam),
        )
    }

    @Test fun `both 10 and 40 mean the credentials will not work`() {
        // The measured distinction, as behaviour. A client keying re-login on 40 alone sits
        // silently on a 10 forever.
        assertEquals(true, SubsonicError.WRONG_CREDENTIALS.meansCredentialsWontWork)
        assertEquals(true, SubsonicError.MISSING_PARAMETER.meansCredentialsWontWork)
        assertEquals(true, SubsonicError.TOKEN_AUTH_NOT_SUPPORTED.meansCredentialsWontWork)
        assertEquals(false, SubsonicError.NOT_FOUND.meansCredentialsWontWork)
        assertEquals(false, SubsonicError.SERVER_TOO_OLD.meansCredentialsWontWork)
    }

    @Test fun `an unmapped numeric code is preserved verbatim alongside UNKNOWN`() {
        val body = """{"subsonic-response":{"status":"failed","error":{"code":999,"message":"?"}}}"""
        assertEquals(
            SubsonicResult.Failed(SubsonicError.UNKNOWN, 999, "?"),
            SubsonicEnvelope.parse(body),
        )
    }

    @Test fun `every malformed shape is Malformed and none of them throws`() {
        // Enumerated, so a parser that starts throwing on one of these fails here rather
        // than at a call site with a stack trace the user sees.
        val cases = listOf(
            "" to "empty",
            "not json at all" to "garbage",
            "[]" to "root is an array",
            """{"other":{}}""" to "no envelope key",
            """{"subsonic-response":{}}""" to "no status",
            """{"subsonic-response":{"status":"weird"}}""" to "unknown status",
            """{"subsonic-response":{"status":"failed"}}""" to "failed with no error object",
            """{"subsonic-response":{"status":"failed","error":{"message":"x"}}}""" to "no code",
            """{"subsonic-response":{"status":"failed","error":{"code":"forty"}}}""" to "non-numeric code",
            """{"subsonic-response":"a string"}""" to "envelope is not an object",
        )
        val misclassified = cases.filterNot { (body, _) ->
            SubsonicEnvelope.parse(body) is SubsonicResult.Malformed
        }
        assertEquals("these did not parse as Malformed", emptyList<Pair<String, String>>(), misclassified)
    }
}
