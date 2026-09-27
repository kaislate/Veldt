// Copyright (c) 2026 kaislate
// SPDX-License-Identifier: GPL-3.0-or-later

package com.kaislate.veldtplayer.playback.replaygain

import com.kaislate.veldtplayer.data.replaygain.ReplayGainMode
import com.kaislate.veldtplayer.data.replaygain.ReplayGainPreamp
import com.kaislate.veldtplayer.data.replaygain.ReplayGainValues
import kotlin.math.pow

/** The decisions of ReplayGain (0.9.2 spec §5), as pure functions. */
object ReplayGainMath {

    /**
     * Whether an item plays at its ALBUM gain. [ReplayGainMode.AUTO] is "the queue plays one album
     * in order": shuffle off, and the item shares its album key with the item before or after it
     * in the queue. One track of an album between tracks of others is not an album being played,
     * so it gets track gain like everything else in a mixed queue.
     *
     * [key], [previousKey] and [nextKey] are `LibraryKeys.albumKey`s, null when unknown — an
     * unknown neighbour never makes an album.
     */
    fun useAlbumGain(
        mode: ReplayGainMode,
        shuffle: Boolean,
        key: String?,
        previousKey: String?,
        nextKey: String?,
    ): Boolean = when (mode) {
        ReplayGainMode.OFF, ReplayGainMode.TRACK -> false
        ReplayGainMode.ALBUM -> true
        ReplayGainMode.AUTO -> !shuffle && key != null && (key == previousKey || key == nextKey)
    }

    /**
     * The linear factor to multiply an item's samples by.
     *
     * - [ReplayGainMode.OFF], or no gain tag at all: 1 (0 dB) — pre-amp included, since
     *   "missing tags → no change" (spec §5).
     * - Otherwise the chosen gain (album or track per [useAlbum]; the other one when the chosen
     *   one is missing, since a track tagged with only one of them is still better levelled than
     *   not), plus [preampDb], clamped to [ReplayGainPreamp]'s range.
     * - Clipping prevention: when the matching peak is known, the factor is capped so that
     *   `peak × factor ≤ 1`. The peak belongs to the gain it was measured with, so a fallback to
     *   the other gain takes the other peak with it.
     */
    fun linearGain(values: ReplayGainValues?, mode: ReplayGainMode, useAlbum: Boolean, preampDb: Float): Float {
        if (mode == ReplayGainMode.OFF || values == null) return 1f
        val album = values.albumGainDb?.let { it to values.albumPeak }
        val track = values.trackGainDb?.let { it to values.trackPeak }
        val (gainDb, peak) = (if (useAlbum) album ?: track else track ?: album) ?: return 1f
        val preamp = preampDb.coerceIn(ReplayGainPreamp.MIN_DB.toFloat(), ReplayGainPreamp.MAX_DB.toFloat())
        val linear = dbToLinear(gainDb + preamp)
        return if (peak != null && peak > 0f && linear * peak > 1f) 1f / peak else linear
    }

    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()
}
