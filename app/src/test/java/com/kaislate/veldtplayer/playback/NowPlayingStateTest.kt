// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

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
        relativeKey = "Music/7.mp3",
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

    /**
     * The null-song branch runs on every track transition, so transport state has to
     * survive it — returning a bare EMPTY here would render the controls dead mid-skip.
     */
    @Test fun `no song still carries transport state through`() {
        val s = NowPlayingState.from(
            song = null,
            playState = PlayState.PLAYING,
            playerDurationMs = 0L,
            shuffle = true,
            repeat = RepeatMode.ONE,
            hasNext = true,
            hasPrevious = false,
        )
        assertFalse(s.isActive)
        assertEquals(PlayState.PLAYING, s.playState)
        assertTrue(s.isPlaying)
        assertTrue(s.shuffle)
        assertEquals(RepeatMode.ONE, s.repeat)
        assertTrue(s.hasNext)
        assertFalse(s.hasPrevious)
    }

    @Test fun `song fields are carried through`() {
        val s = state(song())
        assertTrue(s.isActive)
        assertEquals(7L, s.songId)
        assertEquals("Blue Monday", s.title)
        assertEquals("New Order", s.artist)
        // Asserted against a non-blank album that differs from the title: sourcing album
        // from the wrong field is invisible when every tag under test is blank.
        assertEquals("Power, Corruption & Lies", s.album)
        assertEquals(7L, s.art?.songId)
    }

    /**
     * The populated branch is a second, independent literal construction — it shares no
     * code with the null branch, so nothing the null-song test proves transfers here.
     * Hardcoding any of these would let the player and the UI disagree silently: repeat
     * would cycle and actually take effect while the button rendered stuck on OFF.
     *
     * Asserted twice with every flag inverted, so no hardcoded constant survives.
     */
    @Test fun `song still carries transport state through`() {
        val s = NowPlayingState.from(
            song = song(),
            playState = PlayState.PAUSED,
            playerDurationMs = 0L,
            shuffle = true,
            repeat = RepeatMode.ALL,
            hasNext = true,
            hasPrevious = false,
        )
        assertEquals(PlayState.PAUSED, s.playState)
        assertTrue(s.shuffle)
        assertEquals(RepeatMode.ALL, s.repeat)
        assertTrue(s.hasNext)
        assertFalse(s.hasPrevious)

        val inverted = NowPlayingState.from(
            song = song(),
            playState = PlayState.PLAYING,
            playerDurationMs = 0L,
            shuffle = false,
            repeat = RepeatMode.ONE,
            hasNext = false,
            hasPrevious = true,
        )
        assertEquals(PlayState.PLAYING, inverted.playState)
        assertFalse(inverted.shuffle)
        assertEquals(RepeatMode.ONE, inverted.repeat)
        assertFalse(inverted.hasNext)
        assertTrue(inverted.hasPrevious)
    }

    @Test fun `blank tags degrade to readable labels`() {
        val s = state(song(title = "   "))
        assertEquals("Unknown title", s.title)

        val untagged = state(
            song(title = "   ").copy(artist = "", album = "\t"),
        )
        assertEquals("Unknown title", untagged.title)
        assertEquals("Unknown artist", untagged.artist)
        assertEquals("Unknown album", untagged.album)
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

    /**
     * The stall is what the error bound leaves behind: a track still on screen with the
     * player parked in IDLE and no code path that will call prepare() again. Asserted
     * against every other PlayState so a mis-wire cannot pass by being constantly true.
     */
    @Test fun `a song parked in IDLE reads as stalled`() {
        val playing = state(song())
        assertFalse(playing.isStalled)
        assertTrue(playing.copy(playState = PlayState.IDLE).isStalled)
        assertFalse(playing.copy(playState = PlayState.PAUSED).isStalled)
        assertFalse(playing.copy(playState = PlayState.BUFFERING).isStalled)
        assertFalse(playing.copy(playState = PlayState.ENDED).isStalled)
    }

    /**
     * Nothing playing is not a stall — it is the ordinary pre-playback state, and rendering
     * a disabled transport for it would mean the app opens looking broken.
     */
    @Test fun `IDLE with no song is not a stall`() {
        assertFalse(NowPlayingState.EMPTY.isStalled)
        assertFalse(state(null).copy(playState = PlayState.IDLE).isStalled)
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
