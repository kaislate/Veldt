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

/** The whole persisted shape: [entries] and which sources are [authBlocked]. Kept as one class so
 *  [ScrobbleQueue.readState]/[writeState] have exactly one thing to read and one to write. */
private data class QueueState(val entries: List<QueuedScrobble>, val authBlocked: Set<String>)

/**
 * The store-and-forward half of scrobbling (design spec §4): every "played" scrobble that failed
 * as unreachable, or was rejected for bad credentials, waits here until [ScrobbleFlusher] can
 * retry it — with its ORIGINAL [QueuedScrobble.timeMs], not whenever the retry finally lands.
 *
 * One JSON object file, `[dir]/queue.json` — `{"entries": [...], "authBlocked": [...]}` — holding
 * every entry AND every auth-blocked source id across every account, not one file per source:
 * the whole file is small (entries capped at [CAP]) and a single file is what makes the atomic
 * replace in [writeState] a single rename. Every method re-reads the file from disk rather than
 * keeping an in-memory copy: the same choice [com.kaislate.veldtplayer.data.lyrics.LrclibCache]
 * makes, and what makes two [ScrobbleQueue] instances over the same [dir] agree with each other,
 * which the round-trip test below is what depends on.
 *
 * **Fix round 1 (review fold-in a):** [authBlocked] status is now persisted in the SAME file and
 * the SAME atomic write as the entries, so it survives process death — a device that dies with an
 * account auth-blocked must not silently start hammering that server again on the next launch. A
 * missing or corrupt file reads as "nothing blocked" (as well as "no entries"), matching the
 * existing "corrupt ⇒ empty" rule rather than adding a second one.
 *
 * Thread-safe: every method that touches [dir] runs inside [lock], because [add]/[remove]/
 * [purge]/[setAuthBlocked] are each a read-modify-write of the whole file and two callers racing
 * unsynchronized could each read the same pre-write snapshot and one's write would silently undo
 * the other's.
 */
class ScrobbleQueue(private val dir: File) {

    private val lock = Any()

    private val file: File get() = File(dir, "queue.json")

    /** Appends [entry], oldest-first order preserved. Beyond [CAP] entries, the OLDEST are
     *  dropped, not the newest — a queue that has been offline a long time should not lose the
     *  play-through that just happened in favour of one from a week ago. */
    fun add(entry: QueuedScrobble) = synchronized(lock) {
        val state = readState()
        val entries = (state.entries + entry).let { if (it.size > CAP) it.takeLast(CAP) else it }
        writeState(state.copy(entries = entries))
    }

    /** [sourceId]'s entries, oldest first — the order [ScrobbleFlusher] must deliver in. */
    fun forSource(sourceId: String): List<QueuedScrobble> = synchronized(lock) {
        readState().entries.filter { it.sourceId == sourceId }
    }

    /** Removes exactly one entry equal to [entry] (the first match), e.g. after it is delivered. */
    fun remove(entry: QueuedScrobble) = synchronized(lock) {
        val state = readState()
        val entries = state.entries.toMutableList()
        entries.remove(entry)
        writeState(state.copy(entries = entries))
    }

    /** Drops every entry for [sourceId] and forgets its auth-block, e.g. the account was removed
     *  ([SubsonicSync.purge]) or its source turned out to be unknown to [ScrobbleFlusher]. */
    fun purge(sourceId: String) = synchronized(lock) {
        val state = readState()
        writeState(
            state.copy(
                entries = state.entries.filterNot { it.sourceId == sourceId },
                authBlocked = state.authBlocked - sourceId,
            ),
        )
    }

    fun isEmpty(): Boolean = synchronized(lock) { readState().entries.isEmpty() }

    /** Every source id with at least one queued entry — what [ScrobbleFlusher.flushAll] walks. */
    fun sourcesWithEntries(): Set<String> = synchronized(lock) { readState().entries.map { it.sourceId }.toSet() }

    /** Marks (or clears) [sourceId] as auth-blocked: no delivery attempt until this is cleared.
     *  Persisted — see the class KDoc's fix round 1 note. */
    fun setAuthBlocked(sourceId: String, blocked: Boolean) = synchronized(lock) {
        val state = readState()
        val authBlocked = if (blocked) state.authBlocked + sourceId else state.authBlocked - sourceId
        writeState(state.copy(authBlocked = authBlocked))
    }

    fun isAuthBlocked(sourceId: String): Boolean = synchronized(lock) { sourceId in readState().authBlocked }

    /** A missing or unparseable file reads as no entries and nothing auth-blocked — "corrupt ⇒
     *  empty" (design spec §4, extended by fix round 1 to cover the auth-block half of the same
     *  file); the next write then overwrites it with a valid file, so nothing needs to notice or
     *  repair the corruption explicitly. */
    private fun readState(): QueueState {
        val text = try {
            if (!file.isFile) return EMPTY_STATE
            file.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return EMPTY_STATE
        }
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return EMPTY_STATE
        val entries = (root["entries"] as? JsonArray)?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sourceId = obj.stringOrNull("sourceId") ?: return@mapNotNull null
            val externalId = obj.stringOrNull("externalId") ?: return@mapNotNull null
            val timeMs = obj.longOrNull("timeMs") ?: return@mapNotNull null
            QueuedScrobble(sourceId, externalId, timeMs)
        }.orEmpty()
        val authBlocked = (root["authBlocked"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?.toSet()
            .orEmpty()
        return QueueState(entries, authBlocked)
    }

    /** Atomic replace: temp file in the same directory (so the rename stays on one filesystem),
     *  then rename over the target — mirrors [com.kaislate.veldtplayer.data.lyrics.LrclibCache
     *  .write]'s Windows fallback (delete-then-rename) for when `renameTo` refuses to replace an
     *  existing file. Both halves of [QueueState] go out in this ONE rename — the whole point of
     *  fix round 1's fold-in is that the auth-block can never be persisted a moment apart from
     *  the entries it protects. */
    private fun writeState(state: QueueState) {
        val root = buildJsonObject {
            put(
                "entries",
                buildJsonArray {
                    state.entries.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("sourceId", entry.sourceId)
                                put("externalId", entry.externalId)
                                put("timeMs", entry.timeMs)
                            },
                        )
                    }
                },
            )
            put("authBlocked", buildJsonArray { state.authBlocked.forEach { add(JsonPrimitive(it)) } })
        }
        var temp: File? = null
        try {
            dir.mkdirs()
            temp = File.createTempFile("queue.", ".tmp", dir)
            temp.writeText(root.toString(), Charsets.UTF_8)
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

        val EMPTY_STATE = QueueState(emptyList(), emptySet())
    }
}
