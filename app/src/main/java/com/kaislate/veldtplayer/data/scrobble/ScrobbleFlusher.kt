// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delivers [ScrobbleQueue]'s backlog (design spec §5): "piggyback" (after any successful request
 * to a source, [flush] that source) and the one retry job ([ScrobbleFlushWorker], which calls
 * [flushAll]).
 *
 * [flush] delivers [sourceId]'s entries oldest first, one request per entry, and stops at the
 * FIRST [ScrobbleResult.Unreachable] — a queue of ten entries against a server that just went
 * offline must not spend ten timeouts finding that out; the rest wait for the next flush attempt.
 * A credential rejection also stops the loop (the account needs a new password before ANY of its
 * remaining entries can possibly succeed) and marks the source auth-blocked; any OTHER rejection
 * (e.g. 70 — the track no longer exists on the server) is dropped, since resending an unwanted
 * answer can never change.
 *
 * [SubsonicSources.credentials] returning null (the secret cannot be read — an invalidated
 * Keystore key, or one never sealed) is treated the same as [ScrobbleResult.Unreachable]: stop,
 * touch nothing. Design spec §4 does not name this case, and it is not the same as a REJECTED
 * password — there is no server answer here at all to classify as "credentials won't work" — so
 * auth-blocking on it would block a source the server itself has said nothing bad about.
 *
 * **Fix round 2, finding 2 — double delivery.** Write-ahead (fix round 1, finding 4) means a
 * `Scrobbler` live send and this flush can race over the SAME entry: [ScrobbleQueue.isInFlight]
 * is checked per entry and a `true` entry is SKIPPED (not attempted, not counted as a stop) —
 * the live send owns its own outcome; this flush leaves it alone rather than sending it again
 * before that outcome is known.
 */
@Singleton
class ScrobbleFlusher @Inject constructor(
    private val queue: ScrobbleQueue,
    private val client: SubsonicClient,
    private val sources: SubsonicSources,
) {

    suspend fun flush(sourceId: String) {
        if (queue.isAuthBlocked(sourceId)) return
        if (!sources.contains(sourceId)) {
            // The account is gone. SubsonicSync.purge already does this on a clean removal; this
            // is the belt-and-suspenders path for whatever ordering let a flush run first.
            queue.purge(sourceId)
            return
        }
        // Unreadable secret or a row that vanished since the `contains` check above: treated like
        // unreachable, not like a rejection — no request, no auth-block. See the class KDoc.
        val creds = sources.credentials(sourceId) ?: return
        val caps = sources.capabilities(sourceId)

        for (entry in queue.forSource(sourceId)) {
            if (queue.isInFlight(entry)) continue // a live Scrobbler send owns this one right now
            when (val result = client.scrobble(creds, caps, entry.externalId, submission = true, timeMs = entry.timeMs)) {
                ScrobbleResult.Ok -> queue.remove(entry)
                ScrobbleResult.Unreachable -> return
                is ScrobbleResult.Rejected ->
                    if (result.error.meansCredentialsWontWork) {
                        queue.setAuthBlocked(sourceId, true)
                        return
                    } else {
                        queue.remove(entry)
                    }
            }
        }
    }

    /** Every source with at least one queued entry, each flushed in turn. Order across sources
     *  is unspecified — [ScrobbleQueue.sourcesWithEntries] is a [Set] — because design spec §5
     *  makes no ordering promise between different accounts, only within one account's entries. */
    suspend fun flushAll() {
        for (sourceId in queue.sourcesWithEntries()) flush(sourceId)
    }
}
