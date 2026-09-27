// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.queue

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.test.core.app.ApplicationProvider
import com.kaislate.veldtplayer.playback.VeldtArtUri
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.item
import com.kaislate.veldtplayer.playback.queue.QueueFixtures.queue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Media3 half of queue persistence against a real `ExoPlayer`: that a snapshot reads what the
 * player holds and that a restore puts it back PAUSED and NOT prepared (spec §3). Nothing here
 * prepares the player, so no media is ever opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueuePlayerBridgeTest {

    private val players = mutableListOf<ExoPlayer>()

    private fun player(): ExoPlayer =
        ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build().also { players += it }

    @After fun release() {
        players.forEach { it.release() }
    }

    @Test fun `a restore puts the queue back paused, unprepared, with index, position and modes`() {
        val saved = queue(size = 4, index = 2, positionMs = 42_000L, shuffleOrder = listOf(3, 1, 0, 2))
            .copy(repeatMode = Player.REPEAT_MODE_ALL)
        val exo = player()
        saved.applyTo(exo)

        assertEquals(4, exo.mediaItemCount)
        assertEquals(2, exo.currentMediaItemIndex)
        assertEquals(42_000L, exo.currentPosition)
        assertEquals("local:ext-2", exo.currentMediaItem?.mediaId)
        // Not prepared, not playing: no decoder, no network, until the user presses play.
        assertEquals(Player.STATE_IDLE, exo.playbackState)
        assertFalse(exo.playWhenReady)
        assertEquals(true, exo.shuffleModeEnabled)
        assertEquals(Player.REPEAT_MODE_ALL, exo.repeatMode)
        assertEquals(3, exo.shuffleOrder.firstIndex)
        assertEquals(1, exo.shuffleOrder.getNextIndex(3))
    }

    @Test fun `a restored item carries the logical uri and the private art uri`() {
        val exo = player()
        queue(size = 1).applyTo(exo)
        val restored = exo.getMediaItemAt(0)
        assertEquals(item(0).uri, restored.localConfiguration?.uri.toString())
        assertEquals(item(0).art, restored.mediaMetadata.artworkUri?.let(VeldtArtUri::parse))
        assertEquals("Title 0", restored.mediaMetadata.title.toString())
    }

    @Test fun `snapshot then restore round-trips through a second player`() {
        val saved = queue(size = 3, index = 1, positionMs = 7_000L, shuffleOrder = listOf(2, 0, 1))
        val first = player()
        saved.applyTo(first)
        val snapshot = snapshotOf(first)!!
        assertEquals(saved, snapshot)

        val second = player()
        snapshot.applyTo(second)
        assertEquals(saved, snapshotOf(second))
    }

    @Test fun `a snapshot of an empty player is null`() {
        assertNull(snapshotOf(player()))
    }

    @Test fun `a snapshot is capped around the current item`() {
        val exo = player()
        queue(size = SavedQueues.CAP + 10, index = SavedQueues.CAP + 9).applyTo(exo)
        val snapshot = snapshotOf(exo)!!
        assertEquals(SavedQueues.CAP, snapshot.items.size)
        assertEquals("local:ext-${SavedQueues.CAP + 9}", snapshot.current?.mediaId)
    }

    @Test fun `the resumption answer is the queue at its saved index and position`() {
        val resumption = queue(size = 3, index = 2, positionMs = 1_500L).toResumption()
        assertEquals(listOf("local:ext-0", "local:ext-1", "local:ext-2"), resumption.mediaItems.map { it.mediaId })
        assertEquals(2, resumption.startIndex)
        assertEquals(1_500L, resumption.startPositionMs)
        assertEquals(item(2).uri, resumption.mediaItems[2].localConfiguration?.uri.toString())
    }

    @Test fun `an invalid shuffle order is not applied`() {
        val exo = player()
        exo.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(intArrayOf(), 0L))
        queue(size = 3).copy(shuffleOrder = listOf(0, 0, 1), shuffleEnabled = true).applyTo(exo)
        assertEquals(3, exo.shuffleOrder.length)
    }
}
