// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What `GET /api/get` answered for one title/artist/album/duration lookup — never an exception,
 * see [LrclibClient.get].
 */
sealed interface LrclibAnswer {
    /** 200 with a usable `syncedLyrics` or `plainLyrics`. */
    data class Found(val lyrics: Lyrics) : LrclibAnswer

    /** A confirmed miss: 404, or 200 with `instrumental=true` or both lyrics fields blank. */
    data object NotFound : LrclibAnswer

    /**
     * No usable answer — a non-200/404 status, an unreadable body, or a network failure.
     * [LrclibCache.write] never persists this: a transient error must not be remembered as a
     * confirmed miss, or a track would silently lose its one real shot at ever getting lyrics.
     */
    data object Failed : LrclibAnswer
}

/**
 * A small, standalone client for `https://lrclib.net`'s `GET /api/get` — deliberately not built
 * on [com.kaislate.veldtplayer.data.net.SubsonicClient]: LRCLIB is a third-party host the user
 * did not point this app at, with no Subsonic auth, and it must never share a class with the
 * Subsonic token machinery.
 *
 * [baseUrl] is injectable so tests point it at [com.kaislate.veldtplayer.data.net.FakeHttpServer]
 * instead of the real host. [userAgent] identifies the app, as LRCLIB's own docs ask for.
 *
 * [get] never throws: [callTimeout][OkHttpClient.Builder.callTimeout] is set to 10 seconds on a
 * client built from [http] (never mutating the app-wide one), and every IO failure, unexpected
 * status, or unreadable body degrades to [LrclibAnswer.Failed] — see that type's KDoc for why
 * that must never be confused with [LrclibAnswer.NotFound].
 */
class LrclibClient(
    http: OkHttpClient,
    private val baseUrl: String = "https://lrclib.net",
    private val userAgent: String,
) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(title: String, artist: String, album: String, durationSec: Long): LrclibAnswer =
        withContext(Dispatchers.IO) {
            try {
                val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
                    ?.addPathSegments("api/get")
                    ?.addQueryParameter("artist_name", artist)
                    ?.addQueryParameter("track_name", title)
                    ?.addQueryParameter("album_name", album)
                    ?.addQueryParameter("duration", durationSec.toString())
                    ?.build()
                    ?: return@withContext LrclibAnswer.Failed
                val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
                http.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> parseFound(response.body?.string().orEmpty())
                        404 -> LrclibAnswer.NotFound
                        else -> LrclibAnswer.Failed
                    }
                }
            } catch (e: IOException) {
                LrclibAnswer.Failed
            }
        }

    /**
     * `instrumental=true` wins outright, per spec §5 — checked before either lyrics field so a
     * server that (against its own contract) sends text alongside `instrumental=true` still
     * reads as [LrclibAnswer.NotFound]. Otherwise `syncedLyrics`, if non-blank, is preferred and
     * run through [LrcParser]; a synced text that somehow parses to nothing (or isn't really
     * timed) falls back to `plainLyrics` rather than failing outright.
     */
    private fun parseFound(text: String): LrclibAnswer {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return LrclibAnswer.Failed

        val instrumental = (obj["instrumental"] as? JsonPrimitive)
            ?.takeIf { !it.isString }?.content == "true"
        if (instrumental) return LrclibAnswer.NotFound

        val synced = obj.stringOrNull("syncedLyrics")
        if (!synced.isNullOrBlank()) {
            LrcParser.parse(synced)?.let { return LrclibAnswer.Found(it) }
        }
        val plain = obj.stringOrNull("plainLyrics")
        if (!plain.isNullOrBlank()) return LrclibAnswer.Found(Lyrics.Plain(plain))
        return LrclibAnswer.NotFound
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
