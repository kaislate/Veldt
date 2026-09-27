// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import com.kaislate.veldtplayer.data.art.SongArt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * The saved queue on disk (spec §3): one JSON file, `[dir]/queue.json`, which production puts at
 * `filesDir/playback/queue.json`.
 *
 * Deliberately a file and not Room: the database is destructive on any schema change
 * (`fallbackToDestructiveMigration`), and a table for this would put the user's playlists one
 * migration away from being wiped for the sake of a play queue. It is `filesDir`, not `cacheDir`,
 * because the system may clear a cache at exactly the moment — low storage, long idle — that a
 * resume card is supposed to bring the session back.
 *
 * Shaped after [com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue]: the JSON is walked as
 * `JsonElement` (there is no serialization compiler plugin in this build), and every write is a
 * temp file in the same directory renamed over the target, so a process killed mid-write leaves
 * the previous queue rather than half of the new one.
 *
 * **Corrupt ⇒ nothing saved.** A missing, unparseable or wrong-version file reads as null, and so
 * does a file whose every item is malformed. A malformed ITEM is dropped on its own, with the index
 * moved the same way [SavedQueues.pruned] moves it for a deleted track — one bad entry must not
 * cost the user the other 199.
 *
 * Not thread-safe by itself. The service writes from one single-thread executor and reads once,
 * before any write is scheduled; [lock] only covers the final synchronous flush in `onDestroy`
 * racing a write still on that executor.
 */
class QueueStore(private val dir: File) {

    private val lock = Any()

    private val file: File get() = File(dir, FILE_NAME)

    fun read(): SavedQueue? = synchronized(lock) {
        val text = try {
            if (!file.isFile) return null
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return null
        if (root.long("v") != VERSION.toLong()) return null
        val rawItems = (root["items"] as? JsonArray) ?: return null
        val parsed = rawItems.map { (it as? JsonObject)?.let(::itemOf) }
        // Parse failures are placeholders first and are then pruned exactly like tracks that no
        // longer exist, so a dropped entry moves the index by the same rule.
        val placeholders = parsed.map { it ?: MALFORMED }
        val queue = SavedQueue(
            items = placeholders,
            index = root.long("index")?.toInt() ?: 0,
            positionMs = root.long("positionMs") ?: 0L,
            shuffleEnabled = (root["shuffle"] as? JsonPrimitive)?.booleanOrNull ?: false,
            shuffleOrder = (root["shuffleOrder"] as? JsonArray)
                ?.map { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: -1 },
            repeatMode = root.long("repeat")?.toInt()?.takeIf { it in 0..2 } ?: 0,
        )
        if (queue.items.isEmpty()) return null
        return SavedQueues.pruned(queue) { item -> item.takeUnless { it === MALFORMED } }
    }

    /** Writes [queue], or removes the file when it is null or empty — an empty queue has nothing
     *  to resume, and no file is how resumption knows to opt out. Best-effort, like
     *  `ScrobbleQueue`: a full disk must not crash playback. */
    fun write(queue: SavedQueue?) = synchronized(lock) {
        if (queue == null || queue.items.isEmpty()) {
            runCatching { file.delete() }
            return@synchronized
        }
        val root = buildJsonObject {
            put("v", VERSION)
            put("index", queue.index)
            put("positionMs", queue.positionMs)
            put("shuffle", queue.shuffleEnabled)
            queue.shuffleOrder?.let { order ->
                put("shuffleOrder", buildJsonArray { order.forEach { add(JsonPrimitive(it)) } })
            }
            put("repeat", queue.repeatMode)
            put("items", buildJsonArray { queue.items.forEach { add(jsonOf(it)) } })
        }
        var temp: File? = null
        try {
            dir.mkdirs()
            temp = File.createTempFile("queue.", ".tmp", dir)
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                // Windows-style rename refusing to replace (the JVM test host): the same fallback
                // ScrobbleQueue and LrclibCache use. On Android the first rename replaces.
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (t: Throwable) {
            temp?.delete()
        }
    }

    private fun jsonOf(item: SavedItem): JsonObject = buildJsonObject {
        put("id", item.mediaId)
        put("uri", item.uri)
        put("title", item.title)
        put("artist", item.artist)
        put("album", item.album)
        put("durationMs", item.durationMs)
        item.art?.let { art ->
            put(
                "art",
                buildJsonObject {
                    put("songId", art.songId)
                    put("src", art.uri)
                    art.filePath?.let { put("path", it) }
                    put("emb", art.hasEmbeddedArt)
                },
            )
        }
    }

    /** Null for an entry missing either identity field; display fields default to blank, which
     *  the display layer already renders as "Unknown …". */
    private fun itemOf(obj: JsonObject): SavedItem? {
        val mediaId = obj.string("id")?.takeIf { it.isNotEmpty() } ?: return null
        val uri = obj.string("uri")?.takeIf { it.isNotEmpty() } ?: return null
        val art = (obj["art"] as? JsonObject)?.let { a ->
            val songId = a.long("songId") ?: return@let null
            SongArt(
                songId = songId,
                uri = a.string("src").orEmpty(),
                filePath = a.string("path"),
                hasEmbeddedArt = (a["emb"] as? JsonPrimitive)?.booleanOrNull ?: false,
            )
        }
        return SavedItem(
            mediaId = mediaId,
            uri = uri,
            title = obj.string("title").orEmpty(),
            artist = obj.string("artist").orEmpty(),
            album = obj.string("album").orEmpty(),
            durationMs = obj.long("durationMs") ?: 0L,
            art = art,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()

    companion object {
        const val FILE_NAME = "queue.json"

        /** Bumped on any incompatible change of shape; an old file then reads as nothing saved,
         *  which costs one queue rather than risking a misread one. */
        private const val VERSION = 1

        /** Identity-compared placeholder for an entry that failed to parse; see [read]. */
        private val MALFORMED = SavedItem("", "", "", "", "", 0L, null)
    }
}
