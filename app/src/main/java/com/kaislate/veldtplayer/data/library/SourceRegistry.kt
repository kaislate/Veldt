// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every [LibrarySource] the app has, keyed by its own id (design spec §4.2).
 *
 * This replaces the single Hilt `@Binds` that made "the source" a meaningful phrase. It is not
 * meaningful any more: a song, a playlist entry and a media item each name the source they belong
 * to, and routing goes through *that* id. Code that wants "the" source is code that will break the
 * moment a second one is registered.
 *
 * **Why the character bans live in this constructor.** `':'` is the separator in the
 * `sourceId:externalId` media id, and `'/'` is the path separator in the future `veldt://` uri
 * (design spec §4.4). Both encodings are injective *only* if no id can contain their separator.
 * Enforcing that here — once, in a constructor a test can reach — is what makes the encodings safe;
 * enforcing it at the encoding sites would have to be remembered at every new one (Global
 * Constraint 10).
 *
 * Duplicate ids are rejected rather than silently deduped. A `Set` cannot collapse two distinct
 * instances that merely agree on `id`, so `associateBy` would keep whichever iterated last and the
 * other source would be invisible — a wiring bug that looks like an empty library.
 *
 * **[remote] is the account-backed half (N2 Task 2).** The `Set<LibrarySource>` above is fixed at
 * graph-construction time; a server account is not — it appears and disappears at runtime as
 * [com.kaislate.veldtplayer.data.account.AccountRepository] writes and deletes rows. A remote id
 * that happens to collide with a static one LOSES: the duplicate-id check above only runs over
 * the fixed set, so a colliding remote source cannot be rejected the same way, and the static
 * source — the one Hilt wired deliberately — is what every lookup must keep answering.
 */
@Singleton
class SourceRegistry @Inject constructor(
    sources: Set<@JvmSuppressWildcards LibrarySource>,
    private val remote: RemoteSources = RemoteSources.NONE,
) {
    private val sourcesById: Map<String, LibrarySource> = sources.associateBy { it.id }

    init {
        require(sourcesById.size == sources.size) {
            "duplicate LibrarySource ids: " +
                sources.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
        sourcesById.keys.forEach { id ->
            require(id.isNotBlank() && ':' !in id && '/' !in id) {
                "LibrarySource id \"$id\" must be non-blank and contain no ':' or '/'"
            }
        }
    }

    /** Every registered source, static then remote. Order is unspecified beyond that — callers
     *  that care must sort. */
    val all: Collection<LibrarySource> get() = sourcesById.values + remote.all

    /**
     * The source registered under [sourceId], or null if none is — a *legitimate* answer for a
     * playlist entry whose account was removed, which must render unresolved, not crash.
     *
     * The static map is checked FIRST, so a static source always wins a colliding id; see the
     * class KDoc.
     */
    fun byId(sourceId: String): LibrarySource? = sourcesById[sourceId] ?: remote.byId(sourceId)

    /**
     * The source registered under [sourceId], or a hard failure.
     *
     * For call sites where an absent source is a wiring bug rather than user state: a `Song` that
     * came out of the library table necessarily belongs to a source, so failing loudly at the play
     * call beats silently resolving a wrong-shaped uri.
     */
    fun require(sourceId: String): LibrarySource =
        byId(sourceId) ?: error("no LibrarySource registered for \"$sourceId\"")
}
