// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * `ExoPlayer.Builder`'s own default renderers, with [stage] inserted into the audio sink's
 * processor chain. That is the only change: the sink is built exactly as
 * `DefaultRenderersFactory.buildAudioSink` builds it (1.8.0 disassembly: `DefaultAudioSink
 * .Builder(context)` + the two flags), plus `setAudioProcessors`, which appends [stage] AFTER
 * Media3's own trimming / channel-mapping / to-16-bit processors and BEFORE its silence-skipping
 * and speed (Sonic) ones. So the stage always sees 16-bit PCM with encoder delay already trimmed.
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
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(stage))
        .build()
}
