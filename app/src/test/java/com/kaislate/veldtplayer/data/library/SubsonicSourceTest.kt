// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.data.library.db.SongDao
import com.kaislate.veldtplayer.data.library.db.SongEntity
import com.kaislate.veldtplayer.data.library.db.VeldtDatabase
import com.kaislate.veldtplayer.data.library.db.toDomain
import com.kaislate.veldtplayer.playback.VeldtUri
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Task 2 review fold-in. The REAL [SubsonicSource] over a real Room database — not a fake
 * modelling its own claims — proving [SubsonicSource.stableKey], [SubsonicSource.resolvePlayableUri]
 * and [SubsonicSource.listSongs] scoping, and that [SubsonicSource.search] is local-only by
 * construction: this class has no network dependency to call through even if it wanted to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubsonicSourceTest {

    private lateinit var db: VeldtDatabase
    private lateinit var dao: SongDao
    private lateinit var source: SubsonicSource

    private companion object {
        const val THIS_SOURCE = "sub-A"
        const val OTHER_REMOTE = "sub-B"
        const val LOCAL = "local"
    }

    private fun entity(sourceId: String, externalId: String, title: String = "t") = SongEntity(
        id = 0L, sourceId = sourceId, externalId = externalId, uri = "remote://$sourceId/$externalId",
        filePath = null, relativeKey = null, title = title, artist = "A", album = "Al",
        albumArtist = null, trackNumber = null, discNumber = null, year = null,
        durationMs = 1_000L, dateModifiedSec = 100L, hasEmbeddedArt = false,
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VeldtDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.songDao()
        source = SubsonicSource(THIS_SOURCE, dao)
    }

    @After fun tearDown() = db.close()

    @Test fun `stableKey is exactly sid colon externalId`() {
        val song = entity(THIS_SOURCE, "ext-42").toDomain()
        assertEquals("sid:ext-42", source.stableKey(song))
    }

    @Test fun `resolvePlayableUri is VeldtUri track of this source's id and the song's externalId`() {
        val song = entity(THIS_SOURCE, "ext-42").toDomain()
        assertEquals(VeldtUri.track(THIS_SOURCE, "ext-42"), source.resolvePlayableUri(song))
    }

    /**
     * A second remote source AND the local source are seeded alongside this one — the failure this
     * catches is a query that silently ignores its `sourceId` argument and returns everyone's rows,
     * which would still pass a single-source fixture.
     */
    @Test fun `listSongs returns exactly this source's rows`() = runTest {
        dao.upsertBySourceKey(
            listOf(
                entity(THIS_SOURCE, "a1", title = "mine-1"),
                entity(THIS_SOURCE, "a2", title = "mine-2"),
                entity(OTHER_REMOTE, "b1", title = "not mine (other remote)"),
                entity(LOCAL, "l1", title = "not mine (local)"),
            )
        )

        assertEquals(setOf("a1", "a2"), source.listSongs().map { it.externalId }.toSet())
    }

    @Test fun `search filters this source's rows locally and matches only the intended field`() = runTest {
        dao.upsertBySourceKey(
            listOf(
                entity(THIS_SOURCE, "a1", title = "Abbey Road"),
                entity(THIS_SOURCE, "a2", title = "Nevermind"),
                entity(OTHER_REMOTE, "b1", title = "Abbey Road (other remote, must not match)"),
            )
        )

        assertEquals(setOf("a1"), source.search("abbey").map { it.externalId }.toSet())
    }

    /**
     * "Local-only by construction": [SubsonicSource] takes exactly `(String, SongDao)` — there is
     * no [com.kaislate.veldtplayer.data.net.SubsonicClient] or any other network type it could call
     * through, so `search` cannot reach the network no matter what its body does. Pinned on the
     * constructor's actual parameter types rather than merely trusted from reading the class.
     */
    @Test fun `SubsonicSource has no network dependency to call through`() {
        val ctor = SubsonicSource::class.java.declaredConstructors.single()
        assertEquals(
            listOf(String::class.java, SongDao::class.java),
            ctor.parameterTypes.toList(),
        )
    }
}
