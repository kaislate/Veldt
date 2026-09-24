// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import com.kaislate.veldtplayer.data.library.db.SongEntity

/**
 * The pure decision half of a catalog sync: given what [SongDao.getBySource] already has for one
 * source and what the server just reported, decide what to write and what to drop. Framework-free
 * on purpose — `SongDao.replaceSource` is the only caller, and it owns the actual writes.
 */
object RemoteDiff {

    /** What [SongDao.replaceSource] should do: upsert [upserts] (new or changed rows, as the
     *  server sent them — no id has been assigned or carried over) and delete [removedExternalIds]
     *  from the source being replaced. */
    data class Plan(val upserts: List<SongEntity>, val removedExternalIds: List<String>)

    /**
     * A fetched row is an upsert iff no row in [existing] shares its `externalId`, or the row that
     * does share it differs in any field OTHER than [SongEntity.id]. Comparing with `id` stripped
     * (via `copy(id = 0)`) is what makes this correct at all: [fetched] rows are enumerations that
     * have never been assigned a surrogate, and comparing `id` verbatim would classify every
     * unchanged row as changed, forever re-upserting the whole library on every sync.
     *
     * [removedExternalIds] preserves [existing]'s order — the sync worker's delete does not care,
     * but a test asserting WHICH ids were dropped should not have to also re-sort to check.
     */
    fun plan(existing: List<SongEntity>, fetched: List<SongEntity>): Plan {
        val existingByExternalId = existing.associateBy { it.externalId }
        val fetchedExternalIds = fetched.mapTo(HashSet(fetched.size)) { it.externalId }

        val upserts = fetched.filter { f ->
            val e = existingByExternalId[f.externalId]
            e == null || e.copy(id = 0L) != f.copy(id = 0L)
        }
        val removed = existing.map { it.externalId }.filter { it !in fetchedExternalIds }

        return Plan(upserts, removed)
    }
}
