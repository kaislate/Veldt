// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback

import javax.inject.Inject
import javax.inject.Singleton

/**
 * How one remote source turns a [TrackRef] it owns into something the player can actually open.
 *
 * Two ways in. A resolver for a source that exists at build time joins a Hilt `@IntoSet`
 * multibinding, the same shape [com.kaislate.veldtplayer.data.library.SourceRegistry] uses for
 * `LibrarySource` — see `PlaybackModule`; that set is still empty. Server accounts come and go at
 * runtime, so they cannot be set elements: they are answered by a [RemoteResolverLookup]
 * (`SubsonicStreamResolvers`), consulted only for an id no static resolver claims.
 *
 * [sourceId] must equal the owning `LibrarySource.id`, which is what makes routing here and routing
 * in `SourceRegistry` the same decision rather than two that can drift.
 *
 * Null is a first-class answer, not an error channel: a source that is registered but cannot answer
 * right now — logged out, token expired, base url not configured — returns null and the caller
 * passes the logical uri through untouched. Throwing here would surface as a load exception with a
 * stack trace instead of a track that plainly failed to load.
 */
interface RemoteUriResolver {

    /** The `LibrarySource.id` whose tracks this resolves. */
    val sourceId: String

    /** The real, openable uri for [ref], or null if this resolver cannot produce one now. */
    fun resolve(ref: TrackRef): String?
}

/**
 * Resolvers for sources that exist only at runtime — server accounts (N2 Task 4).
 *
 * Consulted by [PlaybackUriResolver] only when no static resolver claims the id, so a runtime
 * account can never shadow a build-time source. Null means "not mine, or not now", with the same
 * passthrough meaning [RemoteUriResolver.resolve]'s null has.
 */
interface RemoteResolverLookup {

    /** The resolver for [sourceId], or null. Called on the player's loader thread; must not suspend. */
    fun resolverFor(sourceId: String): RemoteUriResolver?

    companion object {
        /** No runtime sources at all: every lookup is a miss. The default for tests and N0/N1. */
        val NONE: RemoteResolverLookup = object : RemoteResolverLookup {
            override fun resolverFor(sourceId: String): RemoteUriResolver? = null
        }
    }
}

/**
 * Turns a logical `veldt://track/…` uri into a real request at LOAD time (spec §4.4), and leaves
 * every other uri strictly alone.
 *
 * This is the whole point of the indirection: because resolution happens here, at load, rather than
 * at enqueue, credentials never enter a `MediaItem`, bitrate and transcode parameters are not frozen
 * into the queue, and the cache identity stays the logical id even when a token rotates. See
 * [VeldtUri].
 *
 * **Everything it cannot resolve, it returns identical** — the same `String` reference it was
 * handed, not a reconstruction. Three cases reach that:
 *
 * 1. A non-`veldt` uri. `content://` above all: local playback must not be routed through anything
 *    new (Global Constraint 5), and this class is on the load path for *every* track.
 * 2. A `veldt` uri whose `sourceId` neither a registered resolver nor the [RemoteResolverLookup]
 *    claims — a playlist entry whose account was removed, or an item some other app pushed at our
 *    exported session.
 * 3. A registered resolver that declines.
 *
 * All three then fail to load, which is correct and visible. The alternative — substituting a
 * fallback, or resolving through whichever resolver happens to be present — would issue a request
 * the user did not ask for, under credentials that belong to a different account.
 *
 * Note what is *not* trusted: a non-null [TrackRef] is only meaningful because [VeldtUri.parse]
 * already established the `veldt` scheme. It is a parse result, not a validation, and both of its
 * segments are attacker-influenceable through the exported `PlaybackService` session — so the
 * `sourceId` is used solely as a map key against resolvers that registered themselves, never to
 * construct anything.
 *
 * ### Construction guards
 *
 * The static set is checked the way `SourceRegistry`'s `init` checks `LibrarySource` ids, with the
 * same message shape: a duplicate `sourceId` is refused rather than letting `associateBy` keep
 * whichever element an unordered Hilt set iterated last (which account's credentials served a
 * track would then depend on iteration order), and an id that is blank or contains `:` or `/` is
 * refused because no `veldt://` uri or media id could name it unambiguously.
 */
@Singleton
class PlaybackUriResolver @Inject constructor(
    resolvers: Set<@JvmSuppressWildcards RemoteUriResolver>,
    private val lookup: RemoteResolverLookup = RemoteResolverLookup.NONE,
) {
    private val bySourceId: Map<String, RemoteUriResolver> = resolvers.associateBy { it.sourceId }

    init {
        require(bySourceId.size == resolvers.size) {
            "duplicate RemoteUriResolver sourceIds: " +
                resolvers.groupBy { it.sourceId }.filterValues { it.size > 1 }.keys
        }
        bySourceId.keys.forEach { id ->
            require(id.isNotBlank() && ':' !in id && '/' !in id) {
                "RemoteUriResolver sourceId \"$id\" must be non-blank and contain no ':' or '/'"
            }
        }
    }

    /** [uri] resolved to a real request, or [uri] itself. See the class KDoc for when and why. */
    fun resolve(uri: String): String {
        val ref = VeldtUri.parse(uri) ?: return uri
        val resolver = bySourceId[ref.sourceId] ?: lookup.resolverFor(ref.sourceId) ?: return uri
        return resolver.resolve(ref) ?: uri
    }
}
