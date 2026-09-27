// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource

/**
 * A `Format` metadata entry naming the media item a stream belongs to. Private to the audio
 * pipeline: it is added by [ItemTaggingAudioRenderer] to the format the RENDERER already holds, so
 * it never reaches track selection, `Player.getMediaMetadata` or anything a controller sees.
 */
internal data class StreamItemTag(val mediaId: String) : Metadata.Entry

internal object StreamItemTags {

    /** [format] with [mediaId]'s tag appended (replacing an older tag rather than stacking). */
    fun tag(format: Format, mediaId: String): Format {
        val kept = format.metadata?.let { m ->
            (0 until m.length()).map(m::get).filterNot { it is StreamItemTag }
        }.orEmpty()
        return format.buildUpon().setMetadata(Metadata(kept + StreamItemTag(mediaId))).build()
    }

    /** The mediaId [format] was tagged with, or null. */
    fun itemOf(format: Format): String? {
        val m = format.metadata ?: return null
        for (i in 0 until m.length()) (m[i] as? StreamItemTag)?.let { return it.mediaId }
        return null
    }

    /** The mediaId of the window [periodId] belongs to in [timeline], or null. */
    fun mediaIdOf(timeline: Timeline, periodId: MediaSource.MediaPeriodId?): String? {
        if (periodId == null || timeline.isEmpty) return null
        val index = timeline.getIndexOfPeriod(periodId.periodUid)
        if (index == -1) return null
        val window = timeline.getPeriod(index, Timeline.Period()).windowIndex
        return timeline.getWindow(window, Timeline.Window()).mediaItem.mediaId.takeIf { it.isNotEmpty() }
    }
}

/**
 * Media3's own `MediaCodecAudioRenderer`, which additionally stamps every stream's input `Format`
 * with the media item it belongs to ([StreamItemTag]) — the identity [GainStage] needs and an
 * `AudioProcessor` is never given (see [GainStage]'s KDoc for the whole boundary mechanism).
 *
 * Why the format is the carrier: the renderer already delays a format until its first sample
 * comes OUT of the decoder (its `formatQueue`, keyed by presentation time), and 1.8.0's
 * `onOutputFormatChanged` copies `Format.metadata` into the format it hands `AudioSink.configure`
 * (bytecode: `Format.Builder.setMetadata`). Tagging on the INPUT side therefore delivers the tag
 * at the OUTPUT boundary with no bookkeeping of our own — including the decoder's own latency,
 * which is where a naive "the renderer is now reading item N+1" signal would be seconds early.
 *
 * Every new stream begins with a format read (a new `SampleStream` has no downstream format yet),
 * which is also what Media3's per-stream encoder-delay trimming depends on, so every stream is
 * tagged. The item comes from the renderer's own `getTimeline()` and `getMediaPeriodId()`, both
 * of which describe the stream this format was just read from.
 */
@OptIn(UnstableApi::class)
internal class ItemTaggingAudioRenderer(
    context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : MediaCodecAudioRenderer(
    context,
    codecAdapterFactory,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink,
) {
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val format = formatHolder.format
        val mediaId = StreamItemTags.mediaIdOf(timeline, mediaPeriodId)
        if (format != null && mediaId != null) formatHolder.format = StreamItemTags.tag(format, mediaId)
        return super.onInputFormatChanged(formatHolder)
    }
}

/**
 * `DefaultAudioSink`, told which item each configuration belongs to: the tag
 * [ItemTaggingAudioRenderer] put on the format is handed to [stage] immediately BEFORE the
 * delegate configures its processors, which is when [GainStage.onConfigure] latches it.
 */
@OptIn(UnstableApi::class)
internal class ItemAwareAudioSink(
    sink: AudioSink,
    private val stage: GainStage,
) : ForwardingAudioSink(sink) {

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        stage.expectItem(StreamItemTags.itemOf(inputFormat))
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
}
