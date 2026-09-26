// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The on-disk half of "each track is fetched from LRCLIB once" (spec §5, "Caching LRCLIB").
 *
 * One file per key, under [dir]: `sha1(key).json` holding `{"kind":"synced|plain|none","text":
 * ...,"at":<ms>}`. `kind="synced"`/`"plain"` are a [LrclibAnswer.Found] — `text` is, respectively,
 * a minimal LRC rendering of the [Lyrics.Synced] lines (re-read through [LrcParser], the same
 * total parser every other provider uses, rather than this class growing its own second decoder
 * for timed lines) or the [Lyrics.Plain] text verbatim. `kind="none"` is a negative cache entry
 * (a 404) with the write time in `at`, honoured for [SEVEN_DAYS_MS] — older than that, [read]
 * returns null so the caller asks again. [LrclibAnswer.Failed] is never written: see that type's
 * KDoc.
 *
 * [now] is injected — production passes `System::currentTimeMillis` — so a test can move time
 * forward without sleeping.
 *
 * Every failure mode degrades quietly: a missing file is a plain cache miss (null); a file that
 * fails to parse, or whose `kind` this build doesn't recognise, is treated as corrupt — deleted,
 * so it doesn't linger forever, and read as null. A write that can't complete (a full disk, a
 * missing cache dir it can't create) is swallowed: the cache is a best-effort optimisation, never
 * something whose failure should surface to the caller looking up lyrics.
 */
class LrclibCache(private val dir: File, private val now: () -> Long) {

    fun read(key: String): LrclibAnswer? {
        val file = fileFor(key)
        val text = try {
            if (!file.isFile) return null
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }

        val obj = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return corrupt(file)
        val kind = obj.stringOrNull("kind") ?: return corrupt(file)
        val at = obj.longOrNull("at") ?: return corrupt(file)

        return when (kind) {
            "none" -> if (now() - at > SEVEN_DAYS_MS) null else LrclibAnswer.NotFound
            "plain" -> {
                val body = obj.stringOrNull("text") ?: return corrupt(file)
                LrclibAnswer.Found(Lyrics.Plain(body))
            }
            "synced" -> {
                val body = obj.stringOrNull("text") ?: return corrupt(file)
                (LrcParser.parse(body) as? Lyrics.Synced)?.let { LrclibAnswer.Found(it) } ?: corrupt(file)
            }
            else -> corrupt(file)
        }
    }

    /** Writes [answer], unless it is [LrclibAnswer.Failed] — see that type's KDoc for why. */
    fun write(key: String, answer: LrclibAnswer) {
        val body = when (answer) {
            is LrclibAnswer.Found -> encodeFound(answer.lyrics)
            LrclibAnswer.NotFound -> buildJsonObject {
                put("kind", "none")
                put("at", now())
            }
            LrclibAnswer.Failed -> return
        }
        try {
            dir.mkdirs()
            fileFor(key).writeText(body.toString(), Charsets.UTF_8)
        } catch (t: Throwable) {
            // Best-effort cache: a write failure must not surface to the caller.
        }
    }

    private fun encodeFound(lyrics: Lyrics): JsonObject = when (lyrics) {
        is Lyrics.Synced -> buildJsonObject {
            put("kind", "synced")
            put("text", toLrcText(lyrics))
            put("at", now())
        }
        is Lyrics.Plain -> buildJsonObject {
            put("kind", "plain")
            put("text", lyrics.text)
            put("at", now())
        }
    }

    /**
     * A minimal `[mm:ss.xxx]text` rendering — enough for [LrcParser.parse] to reconstruct the
     * same [LyricLine]s on [read], without this class needing its own JSON encoding for a list
     * of timed lines.
     */
    private fun toLrcText(lyrics: Lyrics.Synced): String =
        lyrics.lines.joinToString("\n") { line ->
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs % 60_000) / 1_000
            val millis = line.timeMs % 1_000
            "[%02d:%02d.%03d]%s".format(minutes, seconds, millis, line.text)
        }

    /** A corrupt or unrecognised entry is deleted, then read as absent. */
    private fun corrupt(file: File): LrclibAnswer? {
        file.delete()
        return null
    }

    private fun fileFor(key: String): File = File(dir, "${sha1Hex(key)}.json")

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) { digest.forEach { append("%02x".format(it)) } }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private companion object {
        /** How long a negative ("none") entry is honoured — spec §5. */
        const val SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
