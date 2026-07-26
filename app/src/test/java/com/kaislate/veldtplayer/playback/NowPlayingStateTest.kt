package com.kaislate.veldtplayer.playback

import com.kaislate.veldtplayer.data.library.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingStateTest {

    private fun song(title: String = "Blue Monday", duration: Long = 180_000L) = Song(
        id = 7L,
        uri = "content://media/external/audio/media/7",
        filePath = "/music/7.mp3",
        title = title,
        artist = "New Order",
        album = "Power, Corruption & Lies",
        albumArtist = null,
        trackNumber = null,
        discNumber = null,
        year = null,
        durationMs = duration,
        dateModifiedSec = 0L,
        hasEmbeddedArt = true,
    )

    private fun state(song: Song?, playerDuration: Long = 0L) = NowPlayingState.from(
        song = song,
        playState = PlayState.PLAYING,
        playerDurationMs = playerDuration,
        shuffle = false,
        repeat = RepeatMode.OFF,
        hasNext = true,
        hasPrevious = false,
    )

    @Test fun `no song means nothing is active`() {
        val s = state(null)
        assertFalse(s.isActive)
        assertEquals(null, s.songId)
        assertEquals(null, s.art)
    }

    @Test fun `song fields are carried through`() {
        val s = state(song())
        assertTrue(s.isActive)
        assertEquals(7L, s.songId)
        assertEquals("Blue Monday", s.title)
        assertEquals("New Order", s.artist)
        assertEquals(7L, s.art?.songId)
    }

    @Test fun `blank tags degrade to readable labels`() {
        val s = state(song(title = "   "))
        assertEquals("Unknown title", s.title)
    }

    @Test fun `player duration wins when known, library duration is the fallback`() {
        assertEquals(200_000L, state(song(), playerDuration = 200_000L).durationMs)
        assertEquals(180_000L, state(song(), playerDuration = 0L).durationMs)
        assertEquals(180_000L, state(song(), playerDuration = -9_223_372_036_854_775_807L).durationMs)
    }

    @Test fun `initial is the first alphanumeric character`() {
        assertEquals('B', state(song()).initial)
        assertEquals('4', state(song(title = "4 Minutes")).initial)
    }

    @Test fun `initial falls back for titles with no alphanumerics`() {
        assertEquals('♪', state(song(title = "!!!")).initial)
    }

    @Test fun `playing flag follows the play state`() {
        assertTrue(state(song()).isPlaying)
        assertFalse(state(song()).copy(playState = PlayState.PAUSED).isPlaying)
    }

    @Test fun `repeat mode cycles off to all to one and back`() {
        assertEquals(RepeatMode.ALL, RepeatModes.next(RepeatMode.OFF))
        assertEquals(RepeatMode.ONE, RepeatModes.next(RepeatMode.ALL))
        assertEquals(RepeatMode.OFF, RepeatModes.next(RepeatMode.ONE))
    }

    @Test fun `repeat mode maps to and from Media3 ints`() {
        assertEquals(RepeatMode.OFF, RepeatModes.fromPlayer(0))
        assertEquals(RepeatMode.ONE, RepeatModes.fromPlayer(1))
        assertEquals(RepeatMode.ALL, RepeatModes.fromPlayer(2))
        assertEquals(RepeatMode.OFF, RepeatModes.fromPlayer(99))
        assertEquals(0, RepeatModes.toPlayer(RepeatMode.OFF))
        assertEquals(1, RepeatModes.toPlayer(RepeatMode.ONE))
        assertEquals(2, RepeatModes.toPlayer(RepeatMode.ALL))
    }
}
