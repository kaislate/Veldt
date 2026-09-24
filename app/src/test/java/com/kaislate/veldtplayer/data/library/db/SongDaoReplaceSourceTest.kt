// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * `SongDao.replaceSource` — N2 Task 3's transactional "this source's whole contribution is now
 * exactly [fetched]" write. Real Room, real SQLite, on purpose: the property under test (nothing
 * else moves, an unchanged row does not even trigger Room's own invalidation) lives in how the
 * generated SQL actually behaves, not in a model of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SongDaoReplaceSourceTest {

    private lateinit var db: VeldtDatabase
    private lateinit var dao: SongDao
    private val scopes = mutableListOf<CoroutineScope>()

    private fun entity(
        sourceId: String,
        externalId: String,
        title: String = "t",
        modified: Long = 100L,
    ) = SongEntity(
        id = 0L, sourceId = sourceId, externalId = externalId, uri = "content://$sourceId/$externalId",
        filePath = null, relativeKey = null, title = title, artist = "A", album = "Al",
        albumArtist = null, trackNumber = null, discNumber = null, year = null,
        durationMs = 1_000L, dateModifiedSec = modified, hasEmbeddedArt = false,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VeldtDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.songDao()
    }

    @After fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    /** A real background scope, matching `SubsonicSourcesTest`'s pattern: Room's own invalidation
     *  notification runs on Room's query executor, a genuine background thread, never on
     *  `runTest`'s virtual-time dispatcher — so this collects on a real scope and [awaitTrue]
     *  polls for it, rather than pretending virtual time can see a real thread's work. */
    private fun collectorScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    private fun awaitTrue(timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("condition was not observed within ${timeoutMs}ms")
            }
            Thread.sleep(5)
        }
    }

    // ------------------------------------------------------------------------- source isolation

    @Test fun `replacing source A never touches source B's rows`() = runTest {
        dao.upsertBySourceKey(listOf(entity("A", "a1"), entity("A", "a2")))
        dao.upsertBySourceKey(listOf(entity("B", "b1")))

        dao.replaceSource("A", emptyList())

        assertEquals(emptyList<SongEntity>(), dao.getBySource("A"))
        assertEquals(listOf("b1"), dao.getBySource("B").map { it.externalId })
    }

    // ------------------------------------------------------------------------- the anti-churn property

    /**
     * Spec §5.4: "don't redraw an unchanged library." The surrogate must survive (so playlist
     * entries and art caches keyed on it stay valid) AND `observeAllSongs()` must not emit a
     * second time — a re-emission is exactly the signal every collector (the songs screen) would
     * use to redraw, and an unchanged resync must produce none.
     */
    @Test fun `a re-synced unchanged row keeps its surrogate id and does not re-emit`() = runTest {
        dao.upsertBySourceKey(listOf(entity("A", "a1", title = "T")))
        val assignedId = dao.getBySource("A").single().id

        val emissions = Collections.synchronizedList(mutableListOf<List<SongEntity>>())
        val scope = collectorScope()
        scope.launch { dao.observeAllSongs().collect { emissions.add(it) } }
        awaitTrue { emissions.isNotEmpty() }
        val afterFirstEmission = emissions.size

        dao.replaceSource("A", listOf(entity("A", "a1", title = "T"))) // byte-identical content

        // "no second emission" cannot be awaited positively; give a real background invalidation
        // a real window to arrive, then assert none did.
        Thread.sleep(300)
        assertEquals(afterFirstEmission, emissions.size)
        assertEquals(assignedId, dao.getBySource("A").single().id)
    }

    // ------------------------------------------------------------------------- scale (Review Focus 2)

    /**
     * 12,000 rows, 1,500 removed. SQLite's per-statement bound-variable limit on API 29 (this
     * app's minSdk) is 999 — `deleteAllByExternalIds` chunks its `IN (...)` deletes at 500 for
     * exactly that reason, and this is the test that would notice a chunk boundary dropped or
     * double-counted a row. (Robolectric's own SQLite may tolerate a single 1,500-variable `IN`
     * — that is not the thing under test; the chunking itself, and that it drops none and no more
     * than the requested rows, is.)
     */
    @Test fun `scale - 12,000 rows, remove 1,500, exactly those go and the rest keep their ids`() = runTest {
        val all = (1..12_000).map { entity("A", "a$it") }
        dao.upsertBySourceKey(all)
        val idByExternalId = dao.getBySource("A").associate { it.externalId to it.id }
        assertEquals(12_000, idByExternalId.size)

        val removedIds = (1..1_500).map { "a$it" }.toSet()
        val fetched = all.filterNot { it.externalId in removedIds }
        assertEquals(10_500, fetched.size)

        dao.replaceSource("A", fetched)

        val after = dao.getBySource("A")
        assertEquals(10_500, after.size)
        val survivingExternalIds = after.map { it.externalId }.toSet()
        assertEquals("removed ids must be exactly gone", emptySet<String>(), survivingExternalIds intersect removedIds)
        assertEquals("nothing but the removed ids may be gone", 10_500, survivingExternalIds.size)
        after.forEach { row ->
            assertTrue("row ${row.externalId} lost its surrogate id", idByExternalId[row.externalId] == row.id)
        }
    }
}
