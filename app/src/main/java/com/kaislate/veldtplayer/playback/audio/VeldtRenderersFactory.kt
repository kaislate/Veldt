// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * `ExoPlayer.Builder`'s own default renderers, with [stage] — the one owner of the final gain —
 * wired into the audio path. Three changes, and only these:
 *
 * - The sink is built exactly as `DefaultRenderersFactory.buildAudioSink` builds it (1.8.0
 *   disassembly: `DefaultAudioSink.Builder(context)` + the two flags), plus `setAudioProcessors`,
 *   which appends [stage] AFTER Media3's own trimming / channel-mapping / to-16-bit processors and
 *   BEFORE its silence-skipping and speed (Sonic) ones. So the stage always sees 16-bit PCM with
 *   encoder delay already trimmed.
 * - That sink is wrapped in [ItemAwareAudioSink], and
 * - Media3's `MediaCodecAudioRenderer` is replaced by [ItemTaggingAudioRenderer], built with the
 *   same arguments. Together those two tell [stage] which media item each stream is — see
 *   [GainStage]'s KDoc. Any other audio renderer the default list holds (decoder extensions; this
 *   app bundles none) is left exactly where it was.
 *
 * The media source side is untouched — `PlayerDataSources.mediaSourceFactory` stays as it is.
 */
@OptIn(UnstableApi::class)
class VeldtRenderersFactory(
    context: Context,
    private val stage: GainStage,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = ItemAwareAudioSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(stage))
            .build(),
        stage,
    )

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val start = out.size
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        for (i in start until out.size) {
            // Exactly Media3's class, not a subclass of it someone else may have added.
            if (out[i].javaClass == MediaCodecAudioRenderer::class.java) {
                out[i] = ItemTaggingAudioRenderer(
                    context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback,
                    eventHandler, eventListener, audioSink,
                )
            }
        }
    }
}
