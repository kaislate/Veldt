package com.kaislate.veldtplayer.data.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtSourcePlanTest {

    private fun art(
        uri: String = "content://media/external/audio/media/42",
        filePath: String? = "/storage/emulated/0/Music/a.mp3",
        hasEmbeddedArt: Boolean = true,
    ) = SongArt(songId = 42L, uri = uri, filePath = filePath, hasEmbeddedArt = hasEmbeddedArt)

    @Test fun `thumbnail is tried before embedded art`() {
        val plan = ArtSourcePlan.plan(art())
        assertEquals(2, plan.size)
        assertTrue(plan[0] is ArtSource.Thumbnail)
        assertTrue(plan[1] is ArtSource.Embedded)
    }

    @Test fun `embedded art is skipped when the file has none`() {
        val plan = ArtSourcePlan.plan(art(hasEmbeddedArt = false))
        assertEquals(listOf<ArtSource>(ArtSource.Thumbnail(art().uri)), plan)
    }

    @Test fun `embedded art is skipped when there is no readable path`() {
        val plan = ArtSourcePlan.plan(art(filePath = null))
        assertEquals(1, plan.size)
        assertTrue(plan[0] is ArtSource.Thumbnail)
    }

    @Test fun `blank uri drops the thumbnail attempt`() {
        val plan = ArtSourcePlan.plan(art(uri = "  "))
        assertEquals(1, plan.size)
        assertEquals(ArtSource.Embedded("/storage/emulated/0/Music/a.mp3"), plan[0])
    }

    @Test fun `nothing available yields an empty plan for the placeholder`() {
        val plan = ArtSourcePlan.plan(art(uri = "", filePath = null, hasEmbeddedArt = false))
        assertTrue(plan.isEmpty())
    }
}
