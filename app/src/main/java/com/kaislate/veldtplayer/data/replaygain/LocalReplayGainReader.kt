// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.replaygain

import ealvatag.audio.AudioFileIO
import ealvatag.tag.Tag
import ealvatag.tag.TagTextField
import ealvatag.tag.id3.AbstractID3v2Frame
import ealvatag.tag.id3.framebody.FrameBodyTXXX
import ealvatag.tag.mp4.field.Mp4TagReverseDnsField
import java.io.File
import javax.inject.Inject

/**
 * A local file's `REPLAYGAIN_*` tags, read with eAlvaTag at play time (spec §5: nothing is stored,
 * so there is no schema change).
 *
 * eAlvaTag has no `FieldKey` for ReplayGain, so the tag's fields are walked and each format's
 * user-defined field is read by name — see [replayGainFields]. Artwork is skipped
 * (`readIgnoreArtwork`): a cover can be megabytes, and this runs at every track change.
 *
 * Degrades to null on ANY failure, exactly like `EAlvaTagReader` and `EmbeddedLyricsProvider`:
 * a file ReplayGain cannot read plays at 0 dB, it never fails to play.
 */
class LocalReplayGainReader @Inject constructor() {

    fun read(filePath: String?): ReplayGainValues? {
        if (filePath.isNullOrBlank()) return null
        val file = File(filePath)
        if (!file.canRead()) return null
        return try {
            val tag = AudioFileIO.readIgnoreArtwork(file).tag.orNull() ?: return null
            ReplayGainValues.fromTagFields(replayGainFields(tag))
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        /**
         * Every (name, text) pair in [tag] that could be a ReplayGain field, whatever the format:
         *
         * - **ID3v2 (MP3):** `TXXX` frames — the name is the frame's description, the text its value.
         * - **MP4 / M4A:** the iTunes freeform `----:com.apple.iTunes:replaygain_*` atoms — the name
         *   is the atom's descriptor.
         * - **Vorbis comments (FLAC, Ogg):** plain fields, the name is the field id.
         *
         * Order matters: the ID3 and MP4 frames are ALSO [TagTextField]s, whose id is the frame
         * id (`TXXX`) or the whole reverse-DNS id, so they are matched first.
         */
        fun replayGainFields(tag: Tag): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            val fields = tag.fields
            while (fields.hasNext()) {
                val field = fields.next()
                when {
                    field is AbstractID3v2Frame && field.body is FrameBodyTXXX -> {
                        val body = field.body as FrameBodyTXXX
                        out += body.description.orEmpty() to body.firstTextValue.orEmpty()
                    }
                    field is Mp4TagReverseDnsField ->
                        out += field.descriptor.orEmpty() to field.content.orEmpty()
                    field is TagTextField ->
                        out += field.id.orEmpty() to field.content.orEmpty()
                }
            }
            return out
        }
    }
}
