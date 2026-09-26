// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.scrobble.QueuedScrobble
import com.kaislate.veldtplayer.data.scrobble.ScrobbleQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * [SubsonicSyncCoordinator.purge] (N3 task 2: the queue purge hook, controller ruling). Exercises
 * only [SubsonicSyncCoordinator.purge] directly — never [SubsonicSyncCoordinator.request]/
 * [SubsonicSyncCoordinator.cancel]/[SubsonicSyncCoordinator.status], which touch a real
 * `WorkManager` this test never initialises. [purge] itself never calls `WorkManager` at all
 * (see the class it belongs to), so this is safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicSyncCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: VeldtDatabase
    private lateinit var songDao: SongDao
    private lateinit var status: SyncStatusStore
    private lateinit var queueDir: File
    private lateinit var queue: ScrobbleQueue
    private lateinit var coordinator: SubsonicSyncCoordinator

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, VeldtDatabase::class.java).allowMainThreadQueries().build()
        songDao = db.songDao()
        status = SyncStatusStore(context)
        runBlocking { status.clearForTest() }
        queueDir = Files.createTempDirectory("sync-coordinator-test").toFile()
        queue = ScrobbleQueue(queueDir)
        coordinator = SubsonicSyncCoordinator(context, songDao, status, queue)
    }

    @After fun tearDown() {
        db.close()
        queueDir.deleteRecursively()
    }

    private fun songEntity(sourceId: String, externalId: String) = SongEntity(
        id = 0L, sourceId = sourceId, externalId = externalId, uri = "content://$externalId",
        filePath = null, relativeKey = null, title = "t", artist = "A", album = "Al",
        albumArtist = null, trackNumber = null, discNumber = null, year = null,
        durationMs = 1_000L, dateModifiedSec = 100L, hasEmbeddedArt = false,
    )

    @Test fun `purge drops the source's songs, status, and queued scrobbles, leaving other sources alone`() = runTest {
        songDao.upsertBySourceKey(listOf(songEntity("acct-1", "s1"), songEntity("acct-2", "s2")))
        status.recordSuccess("acct-1", 1_000L, 1)
        status.recordSuccess("acct-2", 2_000L, 1)
        queue.add(QueuedScrobble("acct-1", "s1", 1_000L))
        queue.add(QueuedScrobble("acct-2", "s2", 2_000L))
        queue.setAuthBlocked("acct-1", true)

        coordinator.purge("acct-1")

        assertEquals(
            "acct-1's songs must be gone; acct-2's must survive",
            emptyList<String>(),
            songDao.getBySource("acct-1").map { it.externalId },
        )
        assertEquals(listOf("s2"), songDao.getBySource("acct-2").map { it.externalId })

        assertEquals(null, status.status("acct-1").first().lastSuccessMs)
        assertEquals(2_000L, status.status("acct-2").first().lastSuccessMs)

        assertEquals(
            "acct-1's queued scrobbles must be purged",
            emptyList<QueuedScrobble>(),
            queue.forSource("acct-1"),
        )
        assertEquals(
            "acct-2's queued scrobble must survive acct-1's purge",
            listOf(QueuedScrobble("acct-2", "s2", 2_000L)),
            queue.forSource("acct-2"),
        )
        assertEquals("purge must also clear the auth-block", false, queue.isAuthBlocked("acct-1"))
    }
}
