// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [GainStage] as `DefaultAudioSink` drives it — configure, flush, queue, read — on real PCM
 * buffers. Plain JVM: `BaseAudioProcessor` is media3-common, which touches no Android type.
 */
class GainStageTest {

    private val stereo16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun stage(format: AudioProcessor.AudioFormat = stereo16) = GainStage().apply {
        configure(format)
        flush()
    }

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder()).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun GainStage.process16(vararg samples: Int): IntArray {
        queueInput(pcm16(*samples))
        val out = output
        return IntArray(out.remaining() / 2) { out.short.toInt() }
    }

    @Test fun `at unity the samples pass through untouched`() {
        assertArrayEquals(intArrayOf(1000, -1000, 32767, -32768), stage().process16(1000, -1000, 32767, -32768))
    }

    @Test fun `a fade set before a flush applies to the whole next buffer`() {
        val s = stage()
        s.setFade(0.5f)
        s.flush()
        assertArrayEquals(intArrayOf(8192, -8192, 5000, 5000), s.process16(16384, -16384, 10000, 10000))
    }

    /** Frame k of n gets from + (to - from) * (k+1)/n; both channels of a frame the same factor. */
    @Test fun `a fade set mid-stream ramps across the next buffer and ends exactly on it`() {
        val s = stage()
        s.setFade(0.5f)
        // Four stereo frames, 1.0 -> 0.5: factors 0.875, 0.75, 0.625, 0.5.
        assertArrayEquals(
            intArrayOf(8750, 8750, 7500, 7500, 6250, 6250, 5000, 5000),
            s.process16(10000, 10000, 10000, 10000, 10000, 10000, 10000, 10000),
        )
        // The next buffer starts where the ramp ended: flat 0.5.
        assertArrayEquals(intArrayOf(5000, 5000), s.process16(10000, 10000))
    }

    @Test fun `fade is clamped to 0 and 1`() {
        val s = stage()
        s.setFade(-3f)
        s.flush()
        assertArrayEquals(intArrayOf(0, 0), s.process16(20000, -20000))
        s.setFade(7f)
        s.flush()
        assertArrayEquals(intArrayOf(20000, -20000), s.process16(20000, -20000))
    }

    @Test fun `float PCM is scaled too`() {
        val s = stage(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        s.setFade(0.25f)
        s.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).apply {
            putFloat(0.8f); putFloat(-0.4f); flip()
        }
        s.queueInput(input)
        val out = s.output
        assertEquals(0.2f, out.float, 1e-6f)
        assertEquals(-0.1f, out.float, 1e-6f)
    }

    @Test fun `an encoding the sink never hands a processor is refused, not mangled`() {
        assertThrows(AudioProcessor.UnhandledAudioFormatException::class.java) {
            GainStage().configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_24BIT))
        }
    }

    @Test fun `reset restores unity`() {
        val s = stage()
        s.setFade(0f)
        s.reset()
        s.configure(stereo16)
        s.flush()
        assertArrayEquals(intArrayOf(1234, 1234), s.process16(1234, 1234))
    }
}
