// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * The ONE owner of the final output gain (0.9.2 spec §4, §5). Every sample is multiplied by
 *
 *     final = replayGain(item this sample belongs to) × sleepFade
 *
 * and nothing else in the app changes the level: `Player.volume` is never touched, so the two
 * cannot compose by accident — a second owner of the level is exactly the bug "one owner" rules
 * out. The ReplayGain factor already contains the pre-amp and the peak limit (see
 * `ReplayGainMath.linearGain`); the fade is in [0, 1], so composing them can only lower the level
 * and never undoes the clipping prevention.
 *
 * A custom `AudioProcessor` inside `DefaultAudioSink` (see [VeldtRenderersFactory]) rather than
 * `Player.volume`, because a processor sees the PCM itself: it can apply more than unity gain and
 * it knows exactly which samples a change lands on. `AudioTrack.setVolume` can do neither.
 *
 * ## Which item a sample belongs to — the boundary mechanism (spec §5)
 *
 * Media3 1.8 gives an `AudioProcessor` no media item, only PCM. What it does give is a strict
 * protocol, read off the 1.8.0 `DefaultAudioSink` bytecode, that Media3's own encoder-delay
 * trimming relies on for gapless playback:
 *
 * 1. Each new stream's format reaches `AudioSink.configure` when the renderer outputs its FIRST
 *    sample. `configure` calls every processor's `configure` at once — while the previous
 *    stream's audio may still be inside the processors — and parks the result as a PENDING
 *    configuration. `BaseAudioProcessor` keeps a pending format inactive for exactly this reason.
 * 2. The next `handleBuffer` (the new stream's first buffer) first DRAINS the old configuration
 *    to its end, then swaps configurations and calls `flush()` on every processor, which is what
 *    activates a pending format — and only then queues the new buffer.
 *
 * So state latched in [onConfigure] and activated in [onFlush] switches exactly between the last
 * sample of one stream and the first sample of the next: sample-aligned at this processor, gapless
 * or not. [ItemTaggingAudioRenderer] + [ItemAwareAudioSink] supply the identity: the renderer
 * tags each stream's `Format` with its media item, the sink reads the tag in `configure` and hands
 * it to [expectItem] just before the processors are configured. Downstream of here the samples go
 * straight to the `AudioTrack` in order, so the switch is heard exactly at the boundary too. A seek
 * or a sink reset is also a `configure`/`flush` pair, so it lands on the right item the same way.
 *
 * The gain for an item is looked up in [itemGains] per buffer, not frozen at the boundary: the
 * lookahead normally has it ready before the transition, and when it does not (the first track
 * of a fresh queue, a slow server) the correct level arrives a buffer or so later, ramped.
 *
 * ## Where the fade lands in time
 *
 * The processor runs AHEAD of the speaker by whatever the `AudioTrack` has buffered — for 16-bit
 * PCM Media3's default sizing is 250–750 ms. A fade set here is therefore heard up to that much
 * later than it was set. For a 30 s fade that is noise; the one visible consequence is that a
 * sleep-timer pause leaves up to that much faded-to-silence audio queued in the track, which plays
 * (near-silent) for that long when the user next presses play.
 *
 * **No zipper noise.** A new level (a fade tick, a late or changed ReplayGain value) is reached
 * with a linear ramp across the next buffer (tens of milliseconds) rather than a step. [onFlush]
 * snaps instead: a flush is a discontinuity in the stream anyway (a seek, a new stream), and
 * ramping across it would smear one track's level into the next.
 *
 * Threading: [setFade] and [setItemGain] are called from other threads; everything else runs on
 * the playback thread. What crosses threads is `@Volatile` or a concurrent map.
 */
@OptIn(UnstableApi::class)
class GainStage : BaseAudioProcessor() {

    /** The sleep timer's requested fade, 1 = untouched, 0 = silent. */
    @Volatile private var fade = 1f

    /** mediaId → the linear ReplayGain factor for it. Absent means unity (0 dB). */
    private val itemGains = ConcurrentHashMap<String, Float>()

    /** Set by [ItemAwareAudioSink] just before it configures the processors for a new stream. */
    private var expectedItem: String? = null

    /** The item of the configuration waiting for its [flush] — see the class KDoc. */
    private var pendingItem: String? = null

    /** The item whose samples are flowing through now. */
    private var activeItem: String? = null

    /** The gain the last processed frame was actually multiplied by — where the next ramp starts. */
    private var applied = 1f

    /** Called by the sleep timer; clamped to [0, 1]. Takes effect from the next buffer. */
    fun setFade(volume: Float) {
        fade = volume.coerceIn(0f, 1f)
    }

    /** The ReplayGain factor for [mediaId], from the next buffer of that item on. */
    fun setItemGain(mediaId: String, linear: Float) {
        itemGains[mediaId] = linear
    }

    /** Forgets every item's gain except [keep]'s: the map holds the lookahead window, not history. */
    fun retainItemGains(keep: Set<String>) {
        itemGains.keys.retainAll(keep)
    }

    /** The stream about to be configured belongs to [mediaId] (null: unknown, so unity). */
    internal fun expectItem(mediaId: String?) {
        expectedItem = mediaId
    }

    /** The level the next buffer ramps to. */
    private fun target(): Float = (activeItem?.let { itemGains[it] } ?: 1f) * fade

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // DefaultAudioSink converts every PCM input to 16-bit (or to float when float output is
        // enabled) before the processor chain, so these are the only two encodings this stage is
        // ever offered; anything else is a configuration Media3 itself would never build.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingItem = expectedItem
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
        if (activeItem != pendingItem) {
            activeItem = pendingItem
            // One line per stream boundary, never per buffer: what a device check greps for.
            Log.i(LOG_TAG, "boundary: item=$activeItem gain=${activeItem?.let { itemGains[it] }} fade=$fade")
        }
        applied = target()
    }

    override fun onReset() {
        fade = 1f
        applied = 1f
        expectedItem = null
        pendingItem = null
        activeItem = null
    }

    companion object {
        /** `adb logcat -s VeldtGain` shows every boundary, every resolved gain and the sleep
         *  timer's fade trace. */
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
