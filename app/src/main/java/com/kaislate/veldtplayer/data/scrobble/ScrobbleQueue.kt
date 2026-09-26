// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A "played" scrobble that could not be delivered when it happened (design spec §4). Carries
 *  only what the server needs to hear about it late: never a credential, never a display string
 *  — see plan Global Constraint 4. */
data class QueuedScrobble(val sourceId: String, val externalId: String, val timeMs: Long)

/**
 * The store-and-forward half of scrobbling (design spec §4): every "played" scrobble that failed
 * as unreachable, or was rejected for bad credentials, waits here until [ScrobbleFlusher] can
 * retry it — with its ORIGINAL [QueuedScrobble.timeMs], not whenever the retry finally lands.
 *
 * One JSON array file, `[dir]/queue.json`, holding every entry across every source — not one file
 * per source — because the whole file is small (capped at [CAP] entries) and a single file is
 * what makes the atomic replace in [writeEntries] a single rename. Every method re-reads the file
 * from disk rather than keeping an in-memory copy of entries: the same choice [com.kaislate
 * .veldtplayer.data.lyrics.LrclibCache] makes, and what makes two [ScrobbleQueue] instances over
 * the same [dir] agree with each other, which the round-trip test below is what depends on.
 *
 * [authBlocked] is NOT persisted to disk — it is in-memory only, per instance. Production holds
 * exactly one `@Singleton` instance for the process lifetime, so this is invisible in practice;
 * the cost of losing it on a process restart is, at worst, one more doomed delivery attempt with
 * the same bad password before it is set again, which is no worse than any other cold start.
 * Persisting it would mean a second on-disk shape this class has to keep consistent with the
 * entries array for a guarantee the design spec never asks for.
 *
 * Thread-safe: every method that touches [dir] or [authBlocked] runs inside [lock], because
 * [add]/[remove]/[purge] are each a read-modify-write of the whole file and two callers racing
 * unsynchronized could each read the same pre-write snapshot and one's write would silently undo
 * the other's.
 */
class ScrobbleQueue(private val dir: File) {

    private val lock = Any()
    private val authBlocked = mutableSetOf<String>()

    private val file: File get() = File(dir, "queue.json")

    /** Appends [entry], oldest-first order preserved. Beyond [CAP] entries, the OLDEST are
     *  dropped, not the newest — a queue that has been offline a long time should not lose the
     *  play-through that just happened in favour of one from a week ago. */
    fun add(entry: QueuedScrobble) = synchronized(lock) {
        val next = (readEntries() + entry).let { if (it.size > CAP) it.takeLast(CAP) else it }
        writeEntries(next)
    }

    /** [sourceId]'s entries, oldest first — the order [ScrobbleFlusher] must deliver in. */
    fun forSource(sourceId: String): List<QueuedScrobble> = synchronized(lock) {
        readEntries().filter { it.sourceId == sourceId }
    }

    /** Removes exactly one entry equal to [entry] (the first match), e.g. after it is delivered. */
    fun remove(entry: QueuedScrobble) = synchronized(lock) {
        val next = readEntries().toMutableList()
        next.remove(entry)
        writeEntries(next)
    }

    /** Drops every entry for [sourceId] and forgets its auth-block, e.g. the account was removed
     *  ([SubsonicSync.purge]) or its source turned out to be unknown to [ScrobbleFlusher]. */
    fun purge(sourceId: String) = synchronized(lock) {
        writeEntries(readEntries().filterNot { it.sourceId == sourceId })
        authBlocked.remove(sourceId)
    }

    fun isEmpty(): Boolean = synchronized(lock) { readEntries().isEmpty() }

    /** Every source id with at least one queued entry — what [ScrobbleFlusher.flushAll] walks. */
    fun sourcesWithEntries(): Set<String> = synchronized(lock) { readEntries().map { it.sourceId }.toSet() }

    /** Marks (or clears) [sourceId] as auth-blocked: no delivery attempt until this is cleared —
     *  see the class KDoc for why this is in-memory only. */
    fun setAuthBlocked(sourceId: String, blocked: Boolean) = synchronized(lock) {
        if (blocked) authBlocked.add(sourceId) else authBlocked.remove(sourceId)
    }

    fun isAuthBlocked(sourceId: String): Boolean = synchronized(lock) { sourceId in authBlocked }

    /** A missing or unparseable file reads as no entries at all — "corrupt ⇒ empty" (design spec
     *  §4); the next [add] then overwrites it with a valid file, so nothing needs to notice or
     *  repair the corruption explicitly. */
    private fun readEntries(): List<QueuedScrobble> {
        val text = try {
            if (!file.isFile) return emptyList()
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return emptyList()
        }
        val array = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sourceId = obj.stringOrNull("sourceId") ?: return@mapNotNull null
            val externalId = obj.stringOrNull("externalId") ?: return@mapNotNull null
            val timeMs = obj.longOrNull("timeMs") ?: return@mapNotNull null
            QueuedScrobble(sourceId, externalId, timeMs)
        }
    }

    /** Atomic replace: temp file in the same directory (so the rename stays on one filesystem),
     *  then rename over the target — mirrors [com.kaislate.veldtplayer.data.lyrics.LrclibCache
     *  .write]'s Windows fallback (delete-then-rename) for when `renameTo` refuses to replace an
     *  existing file. */
    private fun writeEntries(entries: List<QueuedScrobble>) {
        val array = buildJsonArray {
            entries.forEach { entry ->
                add(
                    buildJsonObject {
                        put("sourceId", entry.sourceId)
                        put("externalId", entry.externalId)
                        put("timeMs", entry.timeMs)
                    },
                )
            }
        }
        var temp: File? = null
        try {
            dir.mkdirs()
            temp = File.createTempFile("queue.", ".tmp", dir)
            temp.writeText(array.toString(), Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                if (!temp.renameTo(file)) temp.delete()
            }
        } catch (t: Throwable) {
            // Best-effort persistence: a write failure must not surface to the caller — the same
            // stance LrclibCache takes, and for the same reason (a full disk must not crash a
            // "played" scrobble that otherwise succeeded).
            temp?.delete()
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.longOrNull(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

    private companion object {
        /** Design spec §4: the queue's cap; beyond this the oldest entries are dropped. */
        const val CAP = 1000
    }
}
