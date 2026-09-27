// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.replaygain

import ealvatag.tag.id3.AbstractID3v2Tag
import ealvatag.tag.id3.ID3v23Frame
import ealvatag.tag.id3.ID3v23Tag
import ealvatag.tag.id3.ID3v24Frame
import ealvatag.tag.id3.ID3v24Tag
import ealvatag.tag.id3.framebody.FrameBodyTXXX
import ealvatag.tag.flac.FlacTag
import ealvatag.tag.mp4.Mp4Tag
import ealvatag.tag.mp4.field.Mp4TagReverseDnsField
import ealvatag.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Reading ReplayGain from tags: the text formats taggers actually write, and each container's
 * user-defined field through REAL eAlvaTag tag objects (built in memory — no audio file needed to
 * prove which field class carries the value). Plain JVM.
 */
class ReplayGainTagsTest {

    private val full = ReplayGainValues(trackGainDb = -6.52f, trackPeak = 0.988547f, albumGainDb = -7.1f, albumPeak = 1.0f)

    // ---- text ----

    @Test fun `gain text as taggers write it`() {
        assertEquals(-6.52f, ReplayGainValues.parseGainDb("-6.52 dB")!!, 0f)
        assertEquals(2.1f, ReplayGainValues.parseGainDb("+2.10 dB")!!, 0f)
        assertEquals(-7f, ReplayGainValues.parseGainDb(" -7dB ")!!, 0f)
        assertEquals("a Unicode minus", -3f, ReplayGainValues.parseGainDb("−3.00 dB")!!, 0f)
        assertEquals("a comma decimal", -4.25f, ReplayGainValues.parseGainDb("-4,25 dB")!!, 0f)
        assertEquals(0f, ReplayGainValues.parseGainDb("0.00 dB")!!, 0f)
        assertNull(ReplayGainValues.parseGainDb("loud"))
        assertNull(ReplayGainValues.parseGainDb(""))
        assertNull("beyond ±60 dB is not a gain anyone measured", ReplayGainValues.parseGainDb("-99 dB"))
        assertNull(ReplayGainValues.parseGainDb("NaN dB"))
    }

    @Test fun `peak text`() {
        assertEquals(0.988547f, ReplayGainValues.parsePeak("0.988547")!!, 0f)
        assertEquals(1.2f, ReplayGainValues.parsePeak(" 1,2 ")!!, 0f)
        assertNull(ReplayGainValues.parsePeak("0"))
        assertNull(ReplayGainValues.parsePeak("-0.5"))
        assertNull(ReplayGainValues.parsePeak("x"))
    }

    @Test fun `fields are matched by name case-insensitively and the first readable value wins`() {
        assertEquals(
            full,
            ReplayGainValues.fromTagFields(
                listOf(
                    "replaygain_track_gain" to "-6.52 dB",
                    "REPLAYGAIN_TRACK_GAIN" to "-1.00 dB",
                    "ReplayGain_Track_Peak" to "0.988547",
                    "REPLAYGAIN_ALBUM_GAIN" to "garbage",
                    "REPLAYGAIN_ALBUM_GAIN" to "-7.10 dB",
                    "REPLAYGAIN_ALBUM_PEAK" to "1.000000",
                    "ARTIST" to "someone",
                ),
            ),
        )
    }

    @Test fun `peaks without a gain are no ReplayGain at all`() {
        assertNull(ReplayGainValues.fromTagFields(listOf("REPLAYGAIN_TRACK_PEAK" to "0.9")))
        assertNull(ReplayGainValues.fromTagFields(emptyList()))
    }

    // ---- containers, through eAlvaTag ----

    private fun txxx24(name: String, value: String) =
        ID3v24Frame("TXXX").apply { body = FrameBodyTXXX(0.toByte(), name, value) }

    private fun txxx23(name: String, value: String) =
        ID3v23Frame("TXXX").apply { body = FrameBodyTXXX(0.toByte(), name, value) }

    private fun AbstractID3v2Tag.withAll(vararg frames: ealvatag.tag.id3.AbstractID3v2Frame) =
        apply { frames.forEach { addField(it) } }

    @Test fun `ID3v2_4 TXXX frames (MP3)`() {
        val tag = ID3v24Tag().withAll(
            txxx24("REPLAYGAIN_TRACK_GAIN", "-6.52 dB"),
            txxx24("REPLAYGAIN_TRACK_PEAK", "0.988547"),
            txxx24("REPLAYGAIN_ALBUM_GAIN", "-7.10 dB"),
            txxx24("REPLAYGAIN_ALBUM_PEAK", "1.000000"),
        )
        assertEquals(full, ReplayGainValues.fromTagFields(LocalReplayGainReader.replayGainFields(tag)))
    }

    @Test fun `ID3v2_3 TXXX frames, lower-case names as foobar2000 writes them`() {
        val tag = ID3v23Tag().withAll(
            txxx23("replaygain_track_gain", "-6.52 dB"),
            txxx23("replaygain_track_peak", "0.988547"),
        )
        assertEquals(
            ReplayGainValues(-6.52f, 0.988547f, null, null),
            ReplayGainValues.fromTagFields(LocalReplayGainReader.replayGainFields(tag)),
        )
    }

    @Test fun `MP4 freeform com_apple_iTunes atoms (M4A)`() {
        val tag = Mp4Tag.makeEmpty().apply {
            addField(Mp4TagReverseDnsField("----", "com.apple.iTunes", "replaygain_track_gain", "-6.52 dB"))
            addField(Mp4TagReverseDnsField("----", "com.apple.iTunes", "replaygain_track_peak", "0.988547"))
            addField(Mp4TagReverseDnsField("----", "com.apple.iTunes", "replaygain_album_gain", "-7.10 dB"))
            addField(Mp4TagReverseDnsField("----", "com.apple.iTunes", "replaygain_album_peak", "1.000000"))
        }
        assertEquals(full, ReplayGainValues.fromTagFields(LocalReplayGainReader.replayGainFields(tag)))
    }

    @Test fun `Vorbis comments (Ogg) and a FLAC tag`() {
        val vorbis = VorbisCommentTag.createNewTag().apply {
            addField("REPLAYGAIN_TRACK_GAIN", "-6.52 dB")
            addField("REPLAYGAIN_TRACK_PEAK", "0.988547")
            addField("REPLAYGAIN_ALBUM_GAIN", "-7.10 dB")
            addField("REPLAYGAIN_ALBUM_PEAK", "1.000000")
        }
        assertEquals(full, ReplayGainValues.fromTagFields(LocalReplayGainReader.replayGainFields(vorbis)))
        val flac = FlacTag(vorbis, emptyList(), false)
        assertEquals(full, ReplayGainValues.fromTagFields(LocalReplayGainReader.replayGainFields(flac)))
    }

    @Test fun `a missing or unreadable file is no ReplayGain, never an exception`() {
        val reader = LocalReplayGainReader()
        assertNull(reader.read(null))
        assertNull(reader.read(""))
        assertNull(reader.read("/definitely/not/here.mp3"))
        val junk = File.createTempFile("not-audio", ".mp3").apply { writeText("this is not an mp3"); deleteOnExit() }
        assertNull(reader.read(junk.path))
    }
}
