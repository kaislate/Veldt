// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.scrobble

import com.kaislate.veldtplayer.data.library.SubsonicSources
import com.kaislate.veldtplayer.data.net.ScrobbleResult
import com.kaislate.veldtplayer.data.net.SubsonicClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether [ScrobbleFlusher.flush]/[flushAll] left any entry queued because a server was
 * genuinely unreachable — as opposed to an auth-block, an unknown/purged source, or a queue that
 * simply had nothing (or nothing more) to deliver. The 2026-09-27 design amendment's bounded
 * retry ("when a flush leaves any entry undelivered because a server was UNREACHABLE, the worker
 * returns `Result.retry()`") must key off THIS, not off "is the queue non-empty afterward" — an
 * auth-blocked source's entries stay queued forever by design, and retrying the worker over those
 * would burn all 6 attempts without ever being able to help.
 */
enum class FlushOutcome {
    /** Nothing was left behind for reachability reasons: every entry for every source visited
     *  was delivered, dropped (non-credential rejection), skipped as in-flight, or belongs to a
     *  source that is auth-blocked / unknown (purged). */
    DELIVERED,

    /** At least one source stopped mid-flush because the server could not be reached (or its
     *  credentials could not even be read — see [flush]'s KDoc) — this source's remaining
     *  entries are exactly the kind of "stranded" backlog the bounded retry exists for. */
    UNREACHABLE,
}

/**
 * Delivers [ScrobbleQueue]'s backlog (design spec §5): "piggyback" (after any successful request
 * to a source, [flush] that source) and the one retry job ([ScrobbleFlushWorker], which calls
 * [flushAll]).
 *
 * [flush] delivers [sourceId]'s entries oldest first, one request per entry, and stops at the
 * FIRST [ScrobbleResult.Unreachable] — a queue of ten entries against a server that just went
 * offline must not spend ten timeouts finding that out; the rest wait for the next flush attempt.
 * A credential rejection also stops the loop (none of the account's remaining entries can succeed
 * until its credentials work again) and marks the source auth-blocked — lifted by a credentials
 * change or by [afterAuthenticatedSuccess] (finding 21); any OTHER rejection
 * (e.g. 70 — the track no longer exists on the server) is dropped, since resending an unwanted
 * answer can never change.
 *
 * [SubsonicSources.credentials] returning null (the secret cannot be read — an invalidated
 * Keystore key, or one never sealed) is treated the same as [ScrobbleResult.Unreachable]: stop,
 * touch nothing, and report [FlushOutcome.UNREACHABLE] — design spec §4 does not name this case,
 * and it is not the same as a REJECTED password — there is no server answer here at all to
 * classify as "credentials won't work" — so auth-blocking on it would block a source the server
 * itself has said nothing bad about. Reporting it as unreachable (rather than delivered) is what
 * lets the bounded retry (amendment 2026-09-27) give a momentarily-unreadable Keystore key the
 * same few bounded chances to recover as a flaky LAN link.
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

    suspend fun flush(sourceId: String): FlushOutcome {
        if (queue.isAuthBlocked(sourceId)) return FlushOutcome.DELIVERED
        if (!sources.contains(sourceId)) {
            // The account is gone. SubsonicSync.purge already does this on a clean removal; this
            // is the belt-and-suspenders path for whatever ordering let a flush run first.
            queue.purge(sourceId)
            return FlushOutcome.DELIVERED
        }
        // Unreadable secret or a row that vanished since the `contains` check above: treated like
        // unreachable, not like a rejection — no request, no auth-block. See the class KDoc.
        val creds = sources.credentials(sourceId) ?: return FlushOutcome.UNREACHABLE
        val caps = sources.capabilities(sourceId)

        for (entry in queue.forSource(sourceId)) {
            if (queue.isInFlight(entry)) continue // a live Scrobbler send owns this one right now
            when (val result = client.scrobble(creds, caps, entry.externalId, submission = true, timeMs = entry.timeMs)) {
                ScrobbleResult.Ok -> queue.remove(entry)
                ScrobbleResult.Unreachable -> return FlushOutcome.UNREACHABLE
                is ScrobbleResult.Rejected ->
                    if (result.error.meansCredentialsWontWork) {
                        queue.setAuthBlocked(sourceId, true)
                        return FlushOutcome.DELIVERED
                    } else {
                        queue.remove(entry)
                    }
            }
        }
        return FlushOutcome.DELIVERED
    }

    /**
     * An authenticated request to [sourceId] just SUCCEEDED — a sync, a scrobble — so its
     * credentials work: clear its auth-block, then [flush] it (finding 21).
     *
     * The block exists because a server said "these credentials won't work"; a server accepting
     * those same credentials is the one answer that proves it no longer applies, whatever fixed it
     * (a restored username, a password changed back on the server, an account re-enabled there).
     * Before this, only a new password saved in Veldt cleared it, and a queued play could be
     * stranded behind a block that a perfectly good sync had already disproved. [flush] alone skips
     * a blocked source, which is exactly why the piggyback could not help.
     */
    suspend fun afterAuthenticatedSuccess(sourceId: String): FlushOutcome {
        queue.setAuthBlocked(sourceId, false)
        return flush(sourceId)
    }

    /** Every source with at least one queued entry, each flushed in turn. Order across sources
     *  is unspecified — [ScrobbleQueue.sourcesWithEntries] is a [Set] — because design spec §5
     *  makes no ordering promise between different accounts, only within one account's entries.
     *  Reports [FlushOutcome.UNREACHABLE] if ANY source's [flush] did — a mix of one delivered
     *  source and one unreachable source must still tell [ScrobbleFlushWorker] to retry, since
     *  the unreachable source's backlog is exactly what the bounded retry exists to catch. */
    suspend fun flushAll(): FlushOutcome {
        var outcome = FlushOutcome.DELIVERED
        for (sourceId in queue.sourcesWithEntries()) {
            if (flush(sourceId) == FlushOutcome.UNREACHABLE) outcome = FlushOutcome.UNREACHABLE
        }
        return outcome
    }
}
