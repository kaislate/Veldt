// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.art

import com.kaislate.veldtplayer.playback.TrackRef
import com.kaislate.veldtplayer.playback.VeldtUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric only because the remote-row tests below build a `veldt://` uri with [VeldtUri
 * .track] and [ArtSourcePlan.plan] parses it back with [VeldtUri.parse] — both call through
 * `android.net.Uri`, which `isReturnDefaultValues` stubs to null outside Robolectric. The
 * original local-ladder tests need no Android type at all and are unaffected by the runner.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.14.x ships no API-36 shadow; pin as SongDaoTest does.
@Config(sdk = [34])
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

    // ---------- remote rows (N2 §5.6) ----------

    /**
     * A remote row's [SongArt.uri] IS its logical `veldt://track/…` uri, and it names a
     * server, not a `content://` MediaStore row — so the whole plan is the one source that can
     * actually answer, never a `Thumbnail` for that same uri (see the next test).
     */
    @Test fun `a remote row's plan is exactly one Remote source, keyed by its TrackRef`() {
        val remote = art(uri = VeldtUri.track("acct", "s1"), filePath = null, hasEmbeddedArt = true)
        assertEquals(listOf(ArtSource.Remote(TrackRef("acct", "s1"))), ArtSourcePlan.plan(remote))
    }

    /**
     * The control for the test above: `ContentResolver.loadThumbnail` cannot open a
     * `veldt://` uri, so a plan that (bug) also tried [ArtSource.Thumbnail] for it would waste
     * an IPC on a guaranteed failure ahead of the one source that can actually answer. Asserted
     * on the whole plan, not merely its size, so a defect that swaps which source is present
     * cannot slip past a bare count check.
     */
    @Test fun `a remote row never also gets a Thumbnail source for its own veldt uri`() {
        val remote = art(uri = VeldtUri.track("acct", "s1"), filePath = null, hasEmbeddedArt = true)
        val plan = ArtSourcePlan.plan(remote)
        assertTrue("plan must not contain a Thumbnail: $plan", plan.none { it is ArtSource.Thumbnail })
    }

    /** A `content://` row is unaffected by the remote branch: [SongArt.remoteRef] is null for
     *  it, so the existing local-ladder tests above still describe its whole behaviour. */
    @Test fun `a content uri is not mistaken for a remote row`() {
        val plan = ArtSourcePlan.plan(art())
        assertTrue(plan.none { it is ArtSource.Remote })
    }
}
