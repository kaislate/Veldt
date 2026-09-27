// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The ONE owner of the final output gain (0.9.2 spec §4, §5): the sleep timer's fade is applied
 * here and nowhere else. `Player.volume` is never touched, so nothing can compose with this stage
 * by accident — a second owner of the level is exactly the bug "one owner" rules out.
 *
 * A custom `AudioProcessor` inside `DefaultAudioSink` (see [VeldtRenderersFactory]) rather than
 * `Player.volume`, because a processor sees the PCM itself: it can apply more than unity gain and
 * it knows exactly which samples a change lands on. `AudioTrack.setVolume` can do neither.
 *
 * **Where the gain lands in time.** The processor runs AHEAD of the speaker by whatever the
 * `AudioTrack` has buffered — for 16-bit PCM Media3's default sizing is 250–750 ms. A fade set
 * here is therefore heard up to that much later than it was set. For a 30 s fade that is noise;
 * the one visible consequence is that a sleep-timer pause leaves up to that much faded-to-silence
 * audio queued in the track, which plays (near-silent) for that long when the user next presses
 * play.
 *
 * **No zipper noise.** The level a caller asks for is reached with a linear ramp across the next
 * buffer (tens of milliseconds) rather than a step, so the timer's ticks — a few per second —
 * never click. [onFlush] snaps instead: a flush is a discontinuity in the stream anyway (a seek,
 * a new stream), and ramping across it would smear the previous context into the new one.
 *
 * Threading: [setFade] is called from the main thread; everything else runs on the playback
 * thread, which is why the requested level is `@Volatile` and the applied one is not.
 */
@OptIn(UnstableApi::class)
class GainStage : BaseAudioProcessor() {

    /** The sleep timer's requested fade, 1 = untouched, 0 = silent. */
    @Volatile private var fade = 1f

    /** The gain the last processed frame was actually multiplied by — where the next ramp starts. */
    private var applied = 1f

    /** Called by the sleep timer; clamped to [0, 1]. Takes effect from the next buffer. */
    fun setFade(volume: Float) {
        fade = volume.coerceIn(0f, 1f)
    }

    /** The level the next buffer ramps to. */
    private fun target(): Float = fade

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // DefaultAudioSink converts every PCM input to 16-bit (or to float when float output is
        // enabled) before the processor chain, so these are the only two encodings this stage is
        // ever offered; anything else is a configuration Media3 itself would never build.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val to = target()
        GainMath.apply(
            input = inputBuffer,
            output = out,
            encoding = inputAudioFormat.encoding,
            channelCount = inputAudioFormat.channelCount,
            from = applied,
            to = to,
        )
        applied = to
        out.flip()
    }

    override fun onFlush() {
        applied = target()
    }

    override fun onReset() {
        fade = 1f
        applied = 1f
    }

    companion object {
        /** `adb logcat -s VeldtGain` shows the sleep timer's fade trace. */
        const val LOG_TAG = "VeldtGain"
    }
}

/**
 * The arithmetic of [GainStage], separated so it is plain JVM code over [ByteBuffer]s.
 *
 * Frame `k` of `n` is multiplied by `from + (to - from) * (k + 1) / n`: the ramp ENDS exactly on
 * [to], so the next buffer (which starts from `to`) continues without a step. Every channel of a
 * frame gets the same factor, so a ramp never shifts the stereo image.
 */
internal object GainMath {

    fun apply(
        input: ByteBuffer,
        output: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        from: Float,
        to: Float,
    ) {
        if (from == 1f && to == 1f) {
            output.put(input)
            return
        }
        when (encoding) {
            C.ENCODING_PCM_16BIT -> apply16(input, output, channelCount, from, to)
            C.ENCODING_PCM_FLOAT -> applyFloat(input, output, channelCount, from, to)
            else -> output.put(input)
        }
    }

    private fun apply16(input: ByteBuffer, output: ByteBuffer, channels: Int, from: Float, to: Float) {
        val frames = input.remaining() / (2 * channels)
        for (k in 0 until frames) {
            val g = gainAt(k, frames, from, to)
            repeat(channels) {
                val scaled = Math.round(input.short * g)
                output.putShort(scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
        }
        // A trailing partial frame (never produced by Media3, but a buffer is only a buffer)
        // is passed through rather than left unread.
        while (input.hasRemaining()) output.put(input.get())
    }

    private fun applyFloat(input: ByteBuffer, output: ByteBuffer, channels: Int, from: Float, to: Float) {
        val frames = input.remaining() / (4 * channels)
        for (k in 0 until frames) {
            val g = gainAt(k, frames, from, to)
            repeat(channels) { output.putFloat((input.float * g).coerceIn(-1f, 1f)) }
        }
        while (input.hasRemaining()) output.put(input.get())
    }

    fun gainAt(frame: Int, frames: Int, from: Float, to: Float): Float =
        if (from == to) to else from + (to - from) * (frame + 1) / frames
}
