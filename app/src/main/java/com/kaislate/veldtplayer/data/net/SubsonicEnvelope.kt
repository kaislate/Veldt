// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The outcome of one Subsonic call, before any endpoint-specific reading. */
sealed interface SubsonicResult {
    /** `status == "ok"`. [body] is the `subsonic-response` object itself. */
    data class Ok(val body: JsonObject) : SubsonicResult

    /**
     * `status == "failed"`. [code] is the server's number verbatim; [error] is that number
     * classified, and is [SubsonicError.UNKNOWN] when this build does not name it.
     */
    data class Failed(val error: SubsonicError, val code: Int, val message: String) : SubsonicResult

    /** Not a Subsonic envelope at all. A proxy error page and a truncated body land here. */
    data class Malformed(val reason: String) : SubsonicResult
}

/**
 * Reads the `subsonic-response` envelope.
 *
 * **No `@Serializable` anywhere.** The kotlinx-serialization compiler plugin is not available
 * to this project offline (Global Constraint 3), so JSON is read by navigating [kotlinx
 * .serialization.json.JsonElement] with safe casts. Safe casts rather than the `jsonObject`
 * /`jsonPrimitive` extensions specifically: those extensions THROW on a shape mismatch, and a
 * hostile or merely broken server must produce a [SubsonicResult.Malformed], never an
 * exception at a call site.
 */
object SubsonicEnvelope {

    const val ENVELOPE_KEY: String = "subsonic-response"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): SubsonicResult {
        val root = runCatching { json.parseToJsonElement(text) }
            .getOrElse { return SubsonicResult.Malformed("not JSON") }

        val obj = root as? JsonObject
            ?: return SubsonicResult.Malformed("root is not an object")

        val env = obj[ENVELOPE_KEY] as? JsonObject
            ?: return SubsonicResult.Malformed("no \"$ENVELOPE_KEY\" object")

        return when ((env["status"] as? JsonPrimitive)?.contentOrNullIfNotString()) {
            "ok" -> SubsonicResult.Ok(env)
            "failed" -> parseFailure(env)
            null -> SubsonicResult.Malformed("no status field")
            else -> SubsonicResult.Malformed("unknown status")
        }
    }

    private fun parseFailure(env: JsonObject): SubsonicResult {
        val error = env["error"] as? JsonObject
            ?: return SubsonicResult.Malformed("status=failed with no error object")
        val codeText = (error["code"] as? JsonPrimitive)?.content
            ?: return SubsonicResult.Malformed("error has no code")
        val code = codeText.toIntOrNull()
            ?: return SubsonicResult.Malformed("error code is not numeric")
        val message = (error["message"] as? JsonPrimitive)?.contentOrNullIfNotString().orEmpty()
        return SubsonicResult.Failed(SubsonicError.of(code), code, message)
    }

    /**
     * The primitive's content when it is a JSON string, else null.
     *
     * `JsonPrimitive.content` returns the literal text for numbers and booleans too, so a
     * `"status": 7` would otherwise read back as the string `"7"` and be reported as an
     * unknown status rather than as the wrong TYPE. [isString] is what keeps that honest.
     */
    private fun JsonPrimitive.contentOrNullIfNotString(): String? = if (isString) content else null
}
