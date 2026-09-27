// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.data.replaygain

/**
 * The ReplayGain setting (0.9.2 spec §5). Default [AUTO], i.e. ON.
 *
 * - [TRACK] levels every track to the same loudness.
 * - [ALBUM] levels whole albums, keeping the quiet track quiet within its album.
 * - [AUTO] is album gain while the queue is playing an album in order, track gain otherwise —
 *   see `ReplayGainMath.useAlbumGain`.
 */
enum class ReplayGainMode { OFF, TRACK, ALBUM, AUTO }

/** The pre-amp's range (spec §5), in whole dB. Default 0. */
object ReplayGainPreamp {
    const val MIN_DB = -6
    const val MAX_DB = 6
}

/**
 * A track's ReplayGain values as its tags (or its server) state them. Each is null when absent:
 * gains in dB relative to the ReplayGain reference, peaks as a linear sample amplitude where 1.0
 * is full scale.
 */
data class ReplayGainValues(
    val trackGainDb: Float?,
    val trackPeak: Float?,
    val albumGainDb: Float?,
    val albumPeak: Float?,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null

    companion object {
        /**
         * The values in [fields] — (name, text) pairs from any tag format — keyed
         * case-insensitively on the de-facto standard names every tagger writes:
         * `REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_TRACK_PEAK`, `REPLAYGAIN_ALBUM_GAIN`,
         * `REPLAYGAIN_ALBUM_PEAK`. The first readable value of each wins. Null when neither gain
         * is present: peaks alone change nothing.
         */
        fun fromTagFields(fields: Iterable<Pair<String, String>>): ReplayGainValues? {
            var trackGain: Float? = null
            var trackPeak: Float? = null
            var albumGain: Float? = null
            var albumPeak: Float? = null
            for ((name, text) in fields) {
                when (name.trim().uppercase()) {
                    "REPLAYGAIN_TRACK_GAIN" -> trackGain = trackGain ?: parseGainDb(text)
                    "REPLAYGAIN_TRACK_PEAK" -> trackPeak = trackPeak ?: parsePeak(text)
                    "REPLAYGAIN_ALBUM_GAIN" -> albumGain = albumGain ?: parseGainDb(text)
                    "REPLAYGAIN_ALBUM_PEAK" -> albumPeak = albumPeak ?: parsePeak(text)
                }
            }
            return ReplayGainValues(trackGain, trackPeak, albumGain, albumPeak).takeUnless { it.isEmpty }
        }

        /**
         * `"-6.52 dB"`, `"+2.1 dB"`, `"-7dB"`, `"−3.00 dB"` (a Unicode minus, which some taggers
         * write) → dB. A comma decimal separator — written by taggers running in a comma locale —
         * is read as a point. Anything unparseable, or beyond a sane ±60 dB, is null.
         */
        fun parseGainDb(text: String): Float? {
            val cleaned = text.trim()
                .replace('−', '-')
                .replace(',', '.')
                .removeSuffix("dB").removeSuffix("db").removeSuffix("DB")
                .trim()
                .removePrefix("+")
            return cleaned.toFloatOrNull()?.takeIf { it.isFinite() && it in -60f..60f }
        }

        /** `"0.988547"` → 0.988547. Non-positive, non-finite or unparseable is null. */
        fun parsePeak(text: String): Float? =
            text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    }
}
