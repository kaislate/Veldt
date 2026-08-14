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

    @Test fun `meansCredentialsWontWork is asserted TOTAL over the enum, never sampled`() {
        // The measured distinction, as behaviour. A client keying re-login on 40 alone sits
        // silently on a 10 forever.
        //
        // Enumerated over entries rather than sampled because the sampled spelling left five
        // constants unasserted, and `|| this == NOT_AUTHORIZED` survived the whole suite. 50
        // is precisely the plausible mis-classification: "not authorized for the operation"
        // means the credentials are FINE and the account lacks a right, so calling it a
        // credential failure pushes a correctly-signed-in user into a re-login prompt they can
        // never satisfy.
        val wontWork = setOf(
            SubsonicError.MISSING_PARAMETER,
            SubsonicError.WRONG_CREDENTIALS,
            SubsonicError.TOKEN_AUTH_NOT_SUPPORTED,
        )
        val willWork = setOf(
            SubsonicError.GENERIC,
            SubsonicError.CLIENT_TOO_OLD,
            SubsonicError.SERVER_TOO_OLD,
            SubsonicError.NOT_AUTHORIZED,
            SubsonicError.TRIAL_EXPIRED,
            SubsonicError.NOT_FOUND,
            SubsonicError.UNKNOWN,
        )
        // A new constant lands in neither set and fails HERE, naming itself, so the author is
        // forced to classify it rather than let it default silently into a bucket.
        assertEquals(
            "a SubsonicError constant is unclassified above",
            SubsonicError.entries.toSet(),
            wontWork + willWork,
        )
        val misclassified = SubsonicError.entries
            .filter { it.meansCredentialsWontWork != (it in wontWork) }
        assertEquals(
            "these are on the wrong side of meansCredentialsWontWork",
            emptyList<SubsonicError>(),
            misclassified,
        )
    }

    @Test fun `every named code maps both ways — of(code) and constant dot code`() {
        // Swapping CLIENT_TOO_OLD(20) and SERVER_TOO_OLD(30) survived the entire suite: the
        // shape is unchanged and one value moves between two correct-looking constants.
        // SERVER_TOO_OLD appeared in a test by NAME only, never through of(30). The user-facing
        // consequence is being told to fix the wrong machine.
        val table = listOf(
            0 to SubsonicError.GENERIC,
            10 to SubsonicError.MISSING_PARAMETER,
            20 to SubsonicError.CLIENT_TOO_OLD,
            30 to SubsonicError.SERVER_TOO_OLD,
            40 to SubsonicError.WRONG_CREDENTIALS,
            41 to SubsonicError.TOKEN_AUTH_NOT_SUPPORTED,
            50 to SubsonicError.NOT_AUTHORIZED,
            60 to SubsonicError.TRIAL_EXPIRED,
            70 to SubsonicError.NOT_FOUND,
        )
        // Total: every constant except the UNKNOWN sentinel must appear in the table above.
        assertEquals(
            "a named SubsonicError constant is missing from the code table",
            SubsonicError.entries.toSet() - SubsonicError.UNKNOWN,
            table.map { it.second }.toSet(),
        )
        val wrongLookup = table.filterNot { (code, constant) -> SubsonicError.of(code) == constant }
        assertEquals("of(code) resolved to the wrong constant", emptyList<Pair<Int, SubsonicError>>(), wrongLookup)
        val wrongValue = table.filterNot { (code, constant) -> constant.code == code }
        assertEquals("constant.code is not the wire number", emptyList<Pair<Int, SubsonicError>>(), wrongValue)
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
