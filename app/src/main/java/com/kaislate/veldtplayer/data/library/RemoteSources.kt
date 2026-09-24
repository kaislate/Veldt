// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

/**
 * The account-backed half of the registry (N2 Task 2 — spec §4.2, §5.2).
 *
 * [SourceRegistry] holds the fixed sources Hilt multibinds at graph-construction time; this
 * interface is the seam for the ones that appear and disappear at RUNTIME as a user adds or
 * removes a server account. It cannot be folded into the `Set<LibrarySource>` multibinding —
 * that set is fixed for the life of the graph, while
 * [com.kaislate.veldtplayer.data.account.AccountRepository] writes and deletes account rows for
 * as long as the process runs.
 *
 * [SubsonicSources] is the real implementation. This interface exists separately so
 * [SourceRegistry] and its consumers can be tested against a trivial fake instead of a Room
 * database and a `SecretBox`.
 */
interface RemoteSources {
    /** The account-backed source registered under [sourceId], or null if there is none. */
    fun byId(sourceId: String): LibrarySource?

    /** Every account currently registered as a source. Order is unspecified. */
    val all: Collection<LibrarySource>

    companion object {
        /** No accounts. [SourceRegistry]'s default — every fixture and build predating N2 gets
         *  exactly the behaviour it always had. */
        val NONE: RemoteSources = object : RemoteSources {
            override fun byId(sourceId: String): LibrarySource? = null
            override val all: Collection<LibrarySource> = emptyList()
        }
    }
}
