// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Where a per-item gain switches (0.9.2 spec §5).
 *
 * The first group drives [GainStage] through the configure/flush protocol `DefaultAudioSink`
 * follows. The last test is the MEASUREMENT: a real Media3 1.8.0 `DefaultAudioSink`, with
 * [ItemAwareAudioSink] in front and Media3's own encoder-delay/padding trimming active, playing
 * two tagged streams back to back as a gapless transition would, while Robolectric's
 * `ShadowAudioTrack` records every byte written to the platform `AudioTrack`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GainStageBoundaryTest {

    private val stereo16 = AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)

    private fun pcm16(frames: Int, value: Int, channels: Int = 2): ByteBuffer =
        ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder()).apply {
            repeat(frames * channels) { putShort(value.toShort()) }
            flip()
        }

    private fun GainStage.processConstant(frames: Int, value: Int): IntArray {
        queueInput(pcm16(frames, value))
        val out = output
        return IntArray(out.remaining() / 2) { out.short.toInt() }
    }

    // ---- the protocol, at the processor ----

    @Test fun `a new item's gain waits for the flush, then applies from its first sample`() {
        val stage = GainStage().apply {
            setItemGain("A", 0.5f)
            setItemGain("B", 0.25f)
        }
        stage.expectItem("A")
        stage.configure(stereo16)
        stage.flush()
        assertArrayEquals(IntArray(4) { 5000 }, stage.processConstant(2, 10000))

        // Item B's format arrives while A's audio is still flowing: configured, NOT yet active.
        stage.expectItem("B")
        stage.configure(stereo16)
        assertArrayEquals("A's tail keeps A's gain", IntArray(4) { 5000 }, stage.processConstant(2, 10000))

        // The sink drains A, then flushes: B is active from its very first frame — no ramp.
        stage.flush()
        assertArrayEquals(IntArray(4) { 2500 }, stage.processConstant(2, 10000))
    }

    @Test fun `an unknown item plays at unity, and a gain resolved late ramps in on the next buffer`() {
        val stage = GainStage()
        stage.expectItem("late")
        stage.configure(stereo16)
        stage.flush()
        assertArrayEquals(IntArray(4) { 10000 }, stage.processConstant(2, 10000))
        stage.setItemGain("late", 0.5f)
        // Two frames, 1.0 -> 0.5: 0.75 then 0.5.
        assertArrayEquals(intArrayOf(7500, 7500, 5000, 5000), stage.processConstant(2, 10000))
        stage.expectItem(null)
        stage.configure(stereo16)
        stage.flush()
        assertArrayEquals("untagged stream: unity", IntArray(4) { 10000 }, stage.processConstant(2, 10000))
    }

    @Test fun `the fade and ReplayGain compose as one product`() {
        val stage = GainStage().apply { setItemGain("A", 1.5f) }
        stage.expectItem("A")
        stage.configure(stereo16)
        stage.setFade(0.5f)
        stage.flush()
        // 10000 × 1.5 × 0.5 = 7500.
        assertArrayEquals(IntArray(4) { 7500 }, stage.processConstant(2, 10000))
        // Positive gain can reach full scale; the 16-bit write saturates rather than wrapping.
        stage.setFade(1f)
        stage.flush()
        assertArrayEquals(IntArray(4) { 32767 }, stage.processConstant(2, 30000))
    }

    @Test fun `retain keeps only the lookahead window`() {
        val stage = GainStage().apply {
            setItemGain("A", 0.5f)
            setItemGain("B", 0.25f)
            retainItemGains(setOf("B"))
        }
        stage.expectItem("A")
        stage.configure(stereo16)
        stage.flush()
        assertArrayEquals("A was forgotten: unity", IntArray(2) { 10000 }, stage.processConstant(1, 10000))
    }

    // ---- the item tag ----

    private fun rawFormat(delay: Int = 0, padding: Int = 0): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(2)
        .setSampleRate(44_100)
        .setEncoderDelay(delay)
        .setEncoderPadding(padding)
        .build()

    @Test fun `a format carries its item tag, replaced rather than stacked, beside other metadata`() {
        val other = object : Metadata.Entry {}
        val base = rawFormat().buildUpon().setMetadata(Metadata(other)).build()
        val a = StreamItemTags.tag(base, "A")
        val b = StreamItemTags.tag(a, "B")
        assertEquals("A", StreamItemTags.itemOf(a))
        assertEquals("B", StreamItemTags.itemOf(b))
        assertEquals("the extractor's own metadata survives, and there is one tag", 2, b.metadata!!.length())
        assertNull(StreamItemTags.itemOf(rawFormat()))
    }

    /** One window per item, one period per window. */
    private class QueueTimeline(private val ids: List<String>) : Timeline() {
        override fun getWindowCount() = ids.size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window =
            window.set(
                "w$windowIndex", MediaItem.Builder().setMediaId(ids[windowIndex]).build(), null,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, true, false, null, 0, C.TIME_UNSET,
                windowIndex, windowIndex, 0,
            )
        override fun getPeriodCount() = ids.size
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period =
            period.set("p$periodIndex", "p$periodIndex", periodIndex, C.TIME_UNSET, 0)
        override fun getIndexOfPeriod(uid: Any): Int = (uid as? String)?.removePrefix("p")?.toIntOrNull() ?: C.INDEX_UNSET
        override fun getUidOfPeriod(periodIndex: Int): Any = "p$periodIndex"
    }

    @Test fun `the renderer's period id names its media item through the timeline`() {
        val timeline = QueueTimeline(listOf("local:1", "acct:tr-9"))
        assertEquals("acct:tr-9", StreamItemTags.mediaIdOf(timeline, MediaSource.MediaPeriodId("p1")))
        assertEquals("local:1", StreamItemTags.mediaIdOf(timeline, MediaSource.MediaPeriodId("p0")))
        assertNull(StreamItemTags.mediaIdOf(timeline, MediaSource.MediaPeriodId("elsewhere")))
        assertNull(StreamItemTags.mediaIdOf(timeline, null))
        assertNull(StreamItemTags.mediaIdOf(Timeline.EMPTY, MediaSource.MediaPeriodId("p0")))
    }

    // ---- the measurement, through a real DefaultAudioSink ----

    private val written = java.io.ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, bytes, _ -> written.write(bytes) }

    @After fun tearDown() {
        ShadowAudioTrack.removeAudioDataListener(listener)
    }

    /**
     * Two streams back to back through Media3's own sink, exactly as the renderer feeds a gapless
     * transition: A's format, A's buffers, then B's format arriving while A is still in the
     * pipeline, then B's buffers. A has 100 frames of encoder padding and B 50 of encoder delay, so
     * Media3's `TrimmingAudioProcessor` cuts both ends around the boundary before [GainStage] sees
     * them. A is 0.5, B is 0.25.
     *
     * Measured result: every frame of A that reaches the `AudioTrack` is exactly 5000 (after the
     * sink's own 20 ms start ramp, see below), every frame of B exactly 2500, and the switch falls
     * between the last written frame of A and the first of B — ZERO frames of misalignment.
     */
    @Test fun `measured - the gain switches on the exact frame where the next stream starts`() =
        assertExactBoundary(measureTwoStreams(upstream = null))

    /**
     * The same, with A's last frames still UPSTREAM of the stage when B's format arrives — what
     * `AudioTrack` back-pressure leaves behind in real playback (the track is full, so the
     * pipeline holds A's tail while the renderer has already moved on to B). Robolectric's track
     * never fills, so [HoldBack] stands in for it: it keeps its last 300 frames until end of
     * stream. Those frames reach [GainStage] only during the sink's drain, after B was configured,
     * and must still get A's gain.
     */
    @Test fun `measured - A's tail still upstream when B is configured keeps A's gain`() =
        assertExactBoundary(measureTwoStreams(upstream = HoldBack(frames = 300)))

    private val framesA = 441 * 4
    private val framesB = 441 * 3

    private fun measureTwoStreams(upstream: AudioProcessor?): IntArray {
        ShadowAudioTrack.addAudioDataListener(listener)
        val stage = GainStage().apply {
            setItemGain("A", 0.5f)
            setItemGain("B", 0.25f)
        }
        val sink = ItemAwareAudioSink(
            DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext())
                .setAudioProcessors(listOfNotNull(upstream, stage).toTypedArray())
                .build(),
            stage,
        )
        sink.setPlayerId(PlayerId.UNSET)

        sink.configure(StreamItemTags.tag(rawFormat(padding = 100), "A"), 0, null)
        feed(sink, pcm16(framesA, 10000), presentationTimeUs = 0)
        sink.configure(StreamItemTags.tag(rawFormat(delay = 50), "B"), 0, null)
        feed(sink, pcm16(framesB, 10000), presentationTimeUs = framesA * 1_000_000L / 44_100)
        sink.playToEndOfStream()

        val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.nativeOrder())
        return IntArray(bytes.remaining() / 2) { bytes.short.toInt() }
    }

    private fun assertExactBoundary(samples: IntArray) {
        val expectedA = (framesA - 100) * 2
        val expectedB = (framesB - 50) * 2
        assertEquals("every frame not trimmed away reached the track", expectedA + expectedB, samples.size)
        // Media3 1.8 itself ramps the first 20 ms written to a NEW AudioTrack up from silence
        // (DefaultAudioSink.maybeRampUpVolume, AUDIO_TRACK_VOLUME_RAMP_TIME_MS = 20) to avoid a
        // click at start. That is the sink's, not this stage's, and it is over long before the
        // boundary: it only runs while fewer than 882 frames have ever been written.
        val rampSamples = 882 * 2
        assertTrue("Media3's start ramp stays under A's level", samples.take(rampSamples).all { it in 0..5000 })
        assertTrue("the rest of A at A's gain", samples.slice(rampSamples until expectedA).all { it == 5000 })
        assertEquals("A's last frame", 5000, samples[expectedA - 1])
        assertEquals("B's first frame: the switch is exactly at the boundary", 2500, samples[expectedA])
        assertTrue("all of B at B's gain", samples.drop(expectedA).all { it == 2500 })
    }

    /** Passes audio through [frames] late: it keeps its newest frames until end of stream. */
    private class HoldBack(private val frames: Int) : BaseAudioProcessor() {
        private var held = ByteArray(0)

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat) = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            val all = held + ByteArray(inputBuffer.remaining()).also { inputBuffer.get(it) }
            val keep = minOf(all.size, frames * inputAudioFormat.bytesPerFrame)
            held = all.copyOfRange(all.size - keep, all.size)
            val release = all.size - keep
            if (release > 0) replaceOutputBuffer(release).put(all, 0, release).flip()
        }

        override fun onQueueEndOfStream() {
            if (held.isNotEmpty()) replaceOutputBuffer(held.size).put(held).flip()
            held = ByteArray(0)
        }

        override fun onFlush() {
            held = ByteArray(0)
        }
    }

    private fun feed(sink: AudioSink, buffer: ByteBuffer, presentationTimeUs: Long) {
        var guard = 0
        while (!sink.handleBuffer(buffer, presentationTimeUs, 1)) {
            check(++guard < 1_000) { "the sink never accepted the buffer" }
        }
    }
}
